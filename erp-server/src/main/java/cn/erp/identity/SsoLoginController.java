package cn.erp.identity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;

@RestController
class SsoLoginController {
    private static final String AUTH=IdentitySecurity.AUTH;
    private static final Duration STATE_TTL=Duration.ofMinutes(5);
    private static final Base64.Encoder URL_ENCODER=Base64.getUrlEncoder().withoutPadding();
    private final LoginIdentities identities;
    private final SsoIdentities ssoIdentities;
    private final LoginLimiter limiter;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final SsoSettings settings;
    private final LdapLoginClient ldap;
    private final SsoWebClient oauth;
    private final AuthSessions sessions;
    private final String prefix;

    SsoLoginController(LoginIdentities identities,SsoIdentities ssoIdentities,LoginLimiter limiter,StringRedisTemplate redis,
                       ObjectMapper json,SsoSettings settings,LdapLoginClient ldap,SsoWebClient oauth,AuthSessions sessions,
                       @Value("${spring.session.redis.namespace:erp:session:prod}") String namespace) {
        this.identities=identities;
        this.ssoIdentities=ssoIdentities;
        this.limiter=limiter;
        this.redis=redis;
        this.json=json;
        this.settings=settings;
        this.ldap=ldap;
        this.oauth=oauth;
        this.sessions=sessions;
        this.prefix=namespace+":sso:";
    }

    @GetMapping(AUTH+"/sso/{providerId}/start")
    ResponseEntity<Void> start(@PathVariable String providerId,@RequestParam @Size(max=32,message="企业编码过长") String tenantCode,
                               HttpServletRequest request) {
        limiter.check(request.getRemoteAddr(),tenantCode,providerId);
        long tenant=tenant(tenantCode);
        var provider=settings.provider(tenant,providerId,true);
        requireSameOrigin(provider,request);
        String state=random();
        String verifier=random();
        String nonce=random();
        var browser=request.getSession(true);
        browser.setAttribute(prefix+"pending",Boolean.TRUE);
        redis.opsForValue().set(key(browser.getId(),state),json.writeValueAsString(new Attempt(tenant,providerId,provider.version(),nonce,verifier)),STATE_TTL);
        return ResponseEntity.status(HttpStatus.FOUND).location(oauth.authorize(provider,state,nonce,URL_ENCODER.encodeToString(sha256(verifier))))
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").build();
    }

    @GetMapping(SsoSettings.CALLBACK)
    ResponseEntity<Void> callback(@RequestParam(required=false) String state,@RequestParam(required=false) String code,
                                  @RequestParam(required=false) String authCode,@RequestParam(required=false) String error,
                                  HttpServletRequest request,HttpServletResponse response) {
        request.setAttribute("erp.login.method","sso");
        try {
            var attempt=consume(request,state);
            request.setAttribute("erp.login.tenantId",attempt.tenant());
            String supplied="dingtalk".equals(attempt.method()) && authCode!=null?authCode:code;
            if (error!=null || supplied==null || supplied.isBlank() || supplied.length()>2048
                    || supplied.chars().anyMatch(Character::isISOControl)) throw LoginIdentities.authFailed();
            var provider=settings.provider(attempt.tenant(),attempt.method(),true);
            if (provider.version()!=attempt.providerVersion()) throw SsoSettings.disabled();
            requireSameOrigin(provider,request);
            var identity=oauth.exchange(provider,supplied,attempt.nonce(),attempt.verifier());
            sessions.open(ssoIdentities.authenticate(attempt.tenant(),provider,identity),request,response);
            return redirect("/login?sso=success");
        } catch(ApiException failure) {
            String step=MfaController.step(failure);
            if (step!=null) return redirect("/login?mfa="+step);
            String result=switch(failure.getErrorCode()) {
                case "SSO_PENDING_APPROVAL" -> "pending";
                case "SSO_NOT_BOUND" -> "unbound";
                default -> "error";
            };
            return redirect("/login?sso="+result);
        } catch(DataAccessException | JacksonException failure) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"单点登录回调未完成，异常类型 {0}",failure.getClass().getSimpleName());
            return redirect("/login?sso=error");
        }
    }

    @PostMapping(AUTH+"/ldap/login")
    ApiResponse<AccessService.UserView> directory(@Valid @RequestBody AuthController.Login input,
                                                  HttpServletRequest request,HttpServletResponse response) {
        request.setAttribute("erp.login.method","sso");
        request.setAttribute("erp.login.tenant",input.tenantCode());
        limiter.check(request.getRemoteAddr(),input.tenantCode(),"ldap-"+input.username());
        long tenant=tenant(input.tenantCode());
        var provider=settings.provider(tenant,"ldap",true);
        String subject=ldap.authenticate(provider,input.username().strip(),input.password());
        var actor=ssoIdentities.authenticate(tenant,provider,new SsoWebClient.Identity(subject,null));
        return ApiResponse.success(sessions.open(actor,request,response));
    }

    private Attempt consume(HttpServletRequest request,String state) {
        var browser=request.getSession(false);
        if (browser==null || state==null || !state.matches("[A-Za-z0-9_-]{43}")) throw LoginIdentities.authFailed();
        String value=redis.opsForValue().getAndDelete(key(browser.getId(),state));
        if (value==null) throw LoginIdentities.authFailed();
        return json.readValue(value,Attempt.class);
    }

    private long tenant(String code) {
        Long id=identities.tenantId(code.strip());
        if (id==null) throw SsoSettings.disabled();
        return id;
    }

    private void requireSameOrigin(SsoSettings.Provider provider,HttpServletRequest request) {
        var callback=SsoSettings.uri(provider.config().get("callbackUrl"),"https");
        try {
            String host=request.getHeader("Host");
            var origin=URI.create("https://"+(host==null?request.getServerName():host));
            if (callback.getHost().equalsIgnoreCase(origin.getHost()) && callback.getHost().equalsIgnoreCase(request.getServerName())
                    && httpsPort(callback)==httpsPort(origin) && origin.getUserInfo()==null && origin.getRawPath().isEmpty()
                    && origin.getRawQuery()==null && origin.getRawFragment()==null) return;
        } catch (IllegalArgumentException failure) {
            throw originMismatch();
        }
        throw originMismatch();
    }

    private String key(String sid,String state) {return prefix+HexFormat.of().formatHex(sha256(sid+"\n"+state));}
    private static String random() {return URL_ENCODER.encodeToString(TotpCodes.random(32));}
    private static byte[] sha256(String value) {
        try {return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}
        catch (NoSuchAlgorithmException failure) {throw new IllegalStateException("运行环境缺少会话摘要算法",failure);}
    }
    private static int httpsPort(URI value) {return value.getPort()<0?443:value.getPort();}
    private static ApiException originMismatch() {return new ApiException(422,"SSO_CALLBACK_HOST_MISMATCH","单点回调必须使用当前站点的 HTTPS 地址");}

    static ResponseEntity<Void> redirect(String target) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(target)).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy","no-referrer").build();
    }

    private record Attempt(long tenant,String method,long providerVersion,String nonce,String verifier) {
        @Override public String toString() {return "单点登录挑战";}
    }
}
