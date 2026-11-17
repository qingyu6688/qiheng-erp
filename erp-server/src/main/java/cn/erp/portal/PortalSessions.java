package cn.erp.portal;

import cn.erp.identity.NotificationChannels;
import cn.erp.identity.SecretProtector;
import cn.erp.shared.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class PortalSessions {
    private static final System.Logger LOG=System.getLogger(PortalSessions.class.getName());
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration RATE_LIFETIME = Duration.ofMinutes(10);
    private static final int ACCOUNT_ATTEMPTS = 10;
    private static final int ADDRESS_ATTEMPTS = 80;
    private static final int CODE_SEND_LIMIT = 3;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> RATE = new DefaultRedisScript<>("""
            local count=redis.call('INCR',KEYS[1]);
            if count==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end;
            return count;
            """,Long.class);
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('GET',KEYS[1])==ARGV[1] then
              redis.call('DEL',KEYS[1]); return 1;
            end;
            return 0;
            """,Long.class);

    private final StringRedisTemplate redis;
    private final PortalAccounts accounts;
    private final SecretProtector secrets;
    private final NotificationChannels channels;
    private final String prefix;
    private final boolean secure;
    private final boolean smsEnabled;
    private final Duration lifetime;

    PortalSessions(StringRedisTemplate redis, PortalAccounts accounts, SecretProtector secrets, NotificationChannels channels,
            @Value("${APP_ENV:prod}") String environment, @Value("${server.servlet.session.cookie.secure:true}") boolean secure,
            @Value("${erp.portal.sms-enabled:false}") boolean smsEnabled,
            @Value("${erp.portal.session-duration:PT8H}") Duration lifetime) {
        this.redis=redis;
        this.accounts=accounts;
        this.secrets=secrets;
        this.channels=channels;
        this.prefix="erp:portal-session:"+environment+":";
        this.secure=secure;
        this.smsEnabled=smsEnabled;
        this.lifetime=lifetime;
        if(lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofDays(1))>0)
            throw new IllegalArgumentException("客户门户会话时长须大于零且不超过一天");
    }

    record PasswordLogin(String tenantCode, String username, String password) {}
    record CodeRequest(String tenantCode, String username) {}
    record CodeLogin(String tenantCode, String username, String code) {}
    private record LoginKey(String tenant, String name) {}

    PortalActor password(HttpServletRequest request, HttpServletResponse response, PasswordLogin input) {
        var login=login(input.tenantCode(),input.username());
        String attempts=attempt(request,login,"password",ACCOUNT_ATTEMPTS);
        var credential=accounts.login(login.tenant(),login.name());
        if(!accounts.matches(input.password(),credential)) throw wrong();
        return issue(request,response,credential,attempts);
    }

    void sendCode(HttpServletRequest request, CodeRequest input) {
        requireSms();
        var login=login(input.tenantCode(),input.username());
        requireMobile(login.name());
        attempt(request,login,"send",CODE_SEND_LIMIT);
        var credential=accounts.login(login.tenant(),login.name());
        if(credential==null || !login.name().equals(credential.contactMobile())) return;
        String code=String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1_000_000));
        String key=codeKey(credential), value=codeDigest(credential,code);
        readRedis(() -> {redis.opsForValue().set(key,value,CODE_LIFETIME);return null;});
        try {
            channels.sendPortalLoginCode(credential.actor().tenantId(),credential.contactMobile(),code);
        } catch (RuntimeException deliveryFailed) {
            consume(key,value);
            LOG.log(System.Logger.Level.WARNING,"门户验证码发送失败，原因类型：{0}",deliveryFailed.getClass().getSimpleName());
        }
    }

    PortalActor code(HttpServletRequest request, HttpServletResponse response, CodeLogin input) {
        requireSms();
        var login=login(input.tenantCode(),input.username());
        requireMobile(login.name());
        String attempts=attempt(request,login,"code",ACCOUNT_ATTEMPTS);
        var credential=accounts.login(login.tenant(),login.name());
        if (credential==null || !login.name().equals(credential.contactMobile()) || input.code()==null
                || !input.code().matches("[0-9]{6}") || !consume(codeKey(credential),codeDigest(credential,input.code()))) throw wrong();
        return issue(request,response,credential,attempts);
    }

    boolean smsEnabled() {return smsEnabled && secrets.configured();}

    public PortalActor authenticate(HttpServletRequest request) {
        String token=cookie(request);
        if (token==null) throw PortalActor.expired();
        String value=readRedis(() -> redis.opsForValue().get(sessionKey(token)));
        if (value==null || !value.matches("[1-9][0-9]{0,18}:\\d{1,10}")) throw PortalActor.expired();
        String[] identity=value.split(":",2);
        var credential=accounts.live(Long.parseLong(identity[0]));
        if(credential==null || !identity[1].equals(Integer.toString(credential.version()))) throw PortalActor.expired();
        return credential.actor();
    }

    void logout(HttpServletRequest request, HttpServletResponse response) {
        revoke(request);
        setCookie(response,"",Duration.ZERO);
    }

    private PortalActor issue(HttpServletRequest request,HttpServletResponse response,PortalAccounts.Credential credential,String attempts) {
        accounts.recordLogin(credential);
        byte[] bytes=new byte[32];
        RANDOM.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        revoke(request);
        readRedis(() -> {
            redis.opsForValue().set(sessionKey(token),credential.actor().userId()+":"+credential.version(),lifetime);
            redis.delete(attempts);
            return null;
        });
        setCookie(response,token,lifetime);
        return credential.actor();
    }

    private String attempt(HttpServletRequest request,LoginKey login,String purpose,int limit) {
        String address=prefix+"rate:ip:"+digest(request.getRemoteAddr());
        String account=prefix+"rate:"+purpose+":"+digest(login.tenant()+"\n"+login.name());
        if(increment(address)>ADDRESS_ATTEMPTS || increment(account)>limit)
            throw new ApiException(429,"PORTAL_TOO_MANY_ATTEMPTS","尝试次数过多，请稍后重试");
        return account;
    }

    private long increment(String key) {
        Long count=readRedis(() -> redis.execute(RATE,List.of(key),Long.toString(RATE_LIFETIME.toSeconds())));
        if(count==null) throw unavailable();
        return count;
    }

    private boolean consume(String key,String value) {
        return Long.valueOf(1).equals(readRedis(() -> redis.execute(CONSUME,List.of(key),value)));
    }

    private void revoke(HttpServletRequest request) {
        String previous=cookie(request);
        if(previous!=null) readRedis(() -> redis.delete(sessionKey(previous)));
    }

    private void setCookie(HttpServletResponse response,String token,Duration age) {
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(PortalSecurity.SESSION_COOKIE,token)
                .path(PortalSecurity.PREFIX).secure(secure).httpOnly(true).sameSite("Strict").maxAge(age).build().toString());
    }

    private String cookie(HttpServletRequest request) {
        String value=null;
        if(request.getCookies()==null) return null;
        for(Cookie cookie:request.getCookies()) {
            if(!PortalSecurity.SESSION_COOKIE.equals(cookie.getName())) continue;
            if(value!=null || !cookie.getValue().matches("[A-Za-z0-9_-]{43}")) return null;
            value=cookie.getValue();
        }
        return value;
    }

    private LoginKey login(String tenant,String user) {
        String code=PortalValues.text(tenant,64,"企业编码").toUpperCase(Locale.ROOT);
        if(!code.matches("[A-Z0-9_-]+")) throw PortalValues.invalid("企业编码格式无效");
        return new LoginKey(code,PortalAccounts.normalizeLogin(user));
    }

    private void requireSms() {
        if(!smsEnabled()) throw new ApiException(503,"PORTAL_SMS_UNAVAILABLE","门户短信登录尚未开启");
    }

    private void requireMobile(String name) {
        if(!name.startsWith("+86")) throw PortalValues.invalid("短信登录须填写门户绑定的手机号");
    }

    private String codeKey(PortalAccounts.Credential credential) {
        return prefix+"code:"+credential.actor().userId()+":"+credential.version();
    }

    private String codeDigest(PortalAccounts.Credential credential,String code) {
        return secrets.fingerprint(credential.actor().tenantId(),"portal-otp",credential.actor().userId()+":"+credential.version()+":"+code);
    }

    private String sessionKey(String token) {return prefix+"token:"+digest(token);}

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("运行环境缺少摘要算法",unavailable);
        }
    }

    private static <T> T readRedis(java.util.function.Supplier<T> call) {
        try {return call.get();}
        catch (DataAccessException unavailable) {throw unavailable();}
    }

    private static ApiException unavailable() {return new ApiException(503,"PORTAL_SESSION_UNAVAILABLE","门户会话服务暂不可用，请稍后重试");}
    private static ApiException wrong() {return new ApiException(401,"PORTAL_CREDENTIALS_INVALID","门户账号或验证码无效，请核对后重试");}
}
