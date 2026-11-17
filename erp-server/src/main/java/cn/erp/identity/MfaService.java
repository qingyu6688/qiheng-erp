package cn.erp.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import jakarta.servlet.http.HttpServletRequest;

@Service
class MfaService {
    private static final Duration ATTEMPT_TTL=Duration.ofMinutes(5);
    private static final Duration SETUP_TTL=Duration.ofMinutes(10);
    private static final TypeReference<List<String>> STRINGS=new TypeReference<>() {};
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("""
            if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end
            return 0
            """,Long.class);
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final SecretProtector protector;
    private final SessionChecks checks;
    private final AuditService audit;
    private final String prefix;

    MfaService(JdbcTemplate jdbc,StringRedisTemplate redis,ObjectMapper json,SecretProtector protector,
               SessionChecks checks,AuditService audit,@Value("${spring.session.redis.namespace:erp:session:prod}") String namespace) {
        this.jdbc=jdbc;
        this.redis=redis;
        this.json=json;
        this.protector=protector;
        this.checks=checks;
        this.audit=audit;
        this.prefix=namespace+":mfa:";
    }

    void challenge(HttpServletRequest request,SessionAccount actor,String method,long enrollmentId) {
        var browser=request.getSession(true);
        browser.setAttribute(prefix+"pending",Boolean.TRUE);
        redis.opsForValue().set(key(request,"pending"),json.writeValueAsString(new Pending(actor,method,enrollmentId)),ATTEMPT_TTL);
    }

    Pending pending(HttpServletRequest request) {
        String value=redis.opsForValue().get(key(request,"pending"));
        if (value==null) throw expired();
        Pending pending=json.readValue(value,Pending.class);
        if (checks.state(pending.actor(),false).enrollmentId()!=pending.enrollmentId()) throw expired();
        return pending;
    }

    String lease(HttpServletRequest request) {
        String token=UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(request,"lease"),token,Duration.ofSeconds(30))))
            throw new ApiException(409,"MFA_BUSY","正在验证，请稍后重试");
        return token;
    }

    void release(String sid,String token) {
        redis.execute(RELEASE,List.of(prefix+"lease:"+SessionAuthority.publicId(sid)),token);
    }

    void clearPending(HttpServletRequest request) {
        redis.delete(List.of(key(request,"pending"),key(request,"setup")));
    }

    State state(SessionAccount actor) {
        var present=checks.state(actor,false);
        return new State(present.enrollmentId()!=0,present.required());
    }

    Setup setup(HttpServletRequest request,SessionAccount actor) {
        var present=checks.state(actor,false);
        if (present.enrollmentId()!=0) throw conflict("账号已经绑定验证器");
        String secret=TotpCodes.secret();
        redis.opsForValue().set(key(request,"setup"),protector.encrypt(actor.tenantId(),purpose(actor),secret),SETUP_TTL);
        String label=URLEncoder.encode("柒衡ERP:"+present.username(),StandardCharsets.UTF_8).replace("+","%20");
        String issuer=URLEncoder.encode("柒衡ERP",StandardCharsets.UTF_8);
        return new Setup(secret,"otpauth://totp/"+label+"?secret="+secret+"&issuer="+issuer+"&algorithm=SHA1&digits=6&period=30");
    }

    @Transactional
    public Enrolled activate(HttpServletRequest request,SessionAccount actor,String code) {
        var current=checks.state(actor,true);
        if (current.enrollmentId()!=0) throw conflict("账号已经绑定验证器");
        String encrypted=redis.opsForValue().get(key(request,"setup"));
        if (encrypted==null) throw new ApiException(400,"MFA_SETUP_EXPIRED","绑定验证器的二维码已失效，请重新生成");
        String secret=protector.decrypt(actor.tenantId(),purpose(actor),encrypted);
        long step=TotpCodes.matchingStep(secret,code,nowStep(),-1);
        if (step<0) throw invalidCode();
        var codes=new ArrayList<String>();
        for (int index=0;index<10;index++) codes.add(TotpCodes.recovery());
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO user_mfa(id,tenant_id,user_id,secret_cipher,recovery_codes_hash,last_used_step)
                VALUES(?,?,?,?,?,?)
                """,id,actor.tenantId(),actor.userId(),encrypted,
                json.writeValueAsString(codes.stream().map(value -> fingerprint(actor,value)).toList()),step);
        audit(actor,"mfa.activate");
        return new Enrolled(actor.withMfa(id),List.copyOf(codes));
    }

    @Transactional
    public SessionAccount verify(SessionAccount actor,String code) {
        var current=checks.state(actor,true);
        var enrollment=checkCode(actor,code,current.enrollmentId());
        return actor.withMfa(enrollment.id());
    }

    @Transactional
    public SessionAccount disable(SessionAccount actor,String code) {
        var current=checks.state(actor,true);
        if (current.required()) throw new ApiException(403,"MFA_POLICY_REQUIRED","企业要求当前角色启用验证器");
        var row=checkCode(actor,code,current.enrollmentId());
        jdbc.update("DELETE FROM user_mfa WHERE tenant_id=? AND user_id=? AND id=?",actor.tenantId(),actor.userId(),row.id());
        audit(actor,"mfa.disable");
        return actor.withMfa(0);
    }

    private Enrollment checkCode(SessionAccount actor,String supplied,long id) {
        if (id==0) throw new ApiException(403,"MFA_ENROLLMENT_REQUIRED","请先绑定验证器");
        var current=jdbc.query("""
                SELECT id,secret_cipher,recovery_codes_hash,last_used_step FROM user_mfa
                WHERE tenant_id=? AND user_id=? AND id=? FOR UPDATE
                """,(row,index) -> new Enrollment(row.getLong(1),row.getString(2),
                json.readValue(row.getString(3),STRINGS),row.getLong(4)),actor.tenantId(),actor.userId(),id)
                .stream().findFirst().orElseThrow(SessionAccount::expired);
        long step=TotpCodes.matchingStep(protector.decrypt(actor.tenantId(),purpose(actor),current.cipher()),
                supplied,nowStep(),current.lastStep());
        if (step>=0) {
            jdbc.update("UPDATE user_mfa SET last_used_step=? WHERE tenant_id=? AND user_id=? AND id=?",
                    step,actor.tenantId(),actor.userId(),id);
            return current;
        }
        if (!TotpCodes.canonicalRecovery(supplied).matches("[0-9A-F]{20}")) throw invalidCode();
        var remaining=new ArrayList<>(current.recovery());
        if (!remaining.remove(fingerprint(actor,supplied))) throw invalidCode();
        jdbc.update("UPDATE user_mfa SET recovery_codes_hash=? WHERE tenant_id=? AND user_id=? AND id=?",
                json.writeValueAsString(remaining),actor.tenantId(),actor.userId(),id);
        return current;
    }

    @Transactional
    public void reset(CompanyAccess administrator,long user) {
        PlatformAccess.lock(jdbc,administrator,SecurityPolicyService.PERMISSION);
        if (administrator.userId()==user) throw new ApiException(400,"VALIDATION_FAILED","请使用个人账号安全设置管理自己的验证器");
        if (jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND user_type='human' FOR UPDATE",
                Long.class,administrator.tenantId(),user).isEmpty()) throw new ApiException(404,"NOT_FOUND","用户不存在");
        jdbc.update("DELETE FROM user_mfa WHERE tenant_id=? AND user_id=?",administrator.tenantId(),user);
        audit.record(administrator,"mfa.reset","sys_user",user,null,Map.of());
    }

    private void audit(SessionAccount actor,String code) {
        audit.record(new CompanyAccess(actor.tenantId(),actor.userId(),0,true,java.util.Set.of(),java.util.Set.of()),
                code,"sys_user",actor.userId(),null,Map.of());
    }

    private String key(HttpServletRequest request,String kind) {
        var browser=request.getSession(false);
        if (browser==null) throw expired();
        return prefix+kind+":"+SessionAuthority.publicId(browser.getId());
    }

    private String fingerprint(SessionAccount actor,String value) {
        return protector.fingerprint(actor.tenantId(),"mfa-recovery:"+actor.userId(),TotpCodes.canonicalRecovery(value));
    }

    private static String purpose(SessionAccount actor) {return "mfa-totp:"+actor.userId();}
    private static long nowStep() {return Instant.now().getEpochSecond()/30;}
    private static ApiException expired() {return new ApiException(401,"MFA_EXPIRED","二次验证已失效，请重新登录");}
    private static ApiException conflict(String message) {return new ApiException(409,"MFA_STATE_CHANGED",message);}
    private static ApiException invalidCode() {return new ApiException(400,"MFA_INVALID_CODE","验证器验证码或恢复码无效、已用或过期");}

    record Pending(SessionAccount actor,String method,long enrollmentId) {}
    record State(boolean enabled,boolean required) {}
    record Setup(String secret,String uri) {
        @Override public String toString() {return "验证器绑定信息";}
    }
    record Enrolled(SessionAccount actor,List<String> recoveryCodes) {
        @Override public String toString() {return "验证器绑定结果";}
    }
    private record Enrollment(long id,String cipher,List<String> recovery,long lastStep) {}
}
