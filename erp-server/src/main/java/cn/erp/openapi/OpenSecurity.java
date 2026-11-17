package cn.erp.openapi;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;

@Configuration(proxyBeanMethods=false)
class OpenSecurity {
    private static final String ACTOR = OpenSecurity.class.getName()+".actor";
    private static final String COMMAND_KEY = OpenSecurity.class.getName()+".idempotency";
    private static final long CLOCK_SKEW_SECONDS = 300;
    private static final DefaultRedisScript<Long> GATE = new DefaultRedisScript<>("""
        local tick = redis.call('TIME')
        local now = tonumber(tick[1]) * 1000 + math.floor(tonumber(tick[2]) / 1000)
        if redis.call('EXISTS', KEYS[1]) == 1 then return -1 end
        redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now-60000)
        if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[1]) then
          local earliest = redis.call('ZRANGE', KEYS[2], 0, 0, 'WITHSCORES')
          return math.max(1, math.ceil((tonumber(earliest[2])+60000-now)/1000))
        end
        redis.call('SET', KEYS[1], '1', 'EX', 601)
        redis.call('ZADD', KEYS[2], now, ARGV[2])
        redis.call('EXPIRE', KEYS[2], 120)
        return 0
        """,Long.class);

    @Bean
    @Order(5)
    SecurityFilterChain openApiSecurityFilterChain(HttpSecurity http,OpenApplications apps,OpenSecrets secrets,StringRedisTemplate redis,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
        http.securityMatcher("/open/v1","/open/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.disable())
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .exceptionHandling(handling -> handling.authenticationEntryPoint((req,res,ex) -> errors.resolveException(req,res,null,unauthorized()))
                        .accessDeniedHandler((req,res,ex) -> errors.resolveException(req,res,null,OpenPolicy.denied())))
                .addFilterBefore(new AppFilter(apps,secrets,redis,errors),AuthorizationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated());
        return http.build();
    }

    @Bean
    @Order(6)
    SecurityFilterChain openRawDocs(HttpSecurity http) throws Exception {
        // 管理页面通过权限校验后读取分组，阻止通用文档路径跳过开放平台授权。
        return http.securityMatcher("/v3/api-docs/open","/v3/api-docs.yaml/open")
                .authorizeHttpRequests(requests -> requests.anyRequest().denyAll()).build();
    }

    static CompanyAccess actor(HttpServletRequest request,String required) {
        if (!(request.getAttribute(ACTOR) instanceof CompanyAccess actor)) throw unauthorized();
        if (!OpenPolicy.SCOPE_CODES.contains(required) || !actor.hasPermission(required)) throw OpenPolicy.denied();
        return actor;
    }

    static String commandKey(HttpServletRequest request) { return (String)request.getAttribute(COMMAND_KEY); }

    private record Signed(OpenApplications.App app,String timestamp,String nonce,String company,String commandKey,byte[] signature) {}

    private static final class AppFilter extends OncePerRequestFilter {
        private final OpenApplications apps;
        private final OpenSecrets secrets;
        private final StringRedisTemplate redis;
        private final HandlerExceptionResolver errors;

        AppFilter(OpenApplications apps,OpenSecrets secrets,StringRedisTemplate redis,HandlerExceptionResolver errors) {
            this.apps=apps;
            this.secrets=secrets;
            this.redis=redis;
            this.errors=errors;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                throws IOException,ServletException {
            var previous=SecurityContextHolder.getContext();
            long started=System.nanoTime();
            OpenApplications.App app=null;
            Long company=null;
            response.setHeader("Cache-Control","no-store");
            try {
                var signed=readHeaders(request);
                var body=new OpenRequest(request);
                verify(body,signed);
                app=signed.app();
                ensureIp(app,request.getRemoteAddr());
                long requestedCompany=OpenPolicy.id(signed.company());
                if (!apps.allowedCompany(app,requestedCompany)) throw OpenPolicy.denied();
                company=requestedCompany;
                reserve(app,signed.nonce(),response);
                var actor=new CompanyAccess(app.tenant(),app.user(),company,true,Set.of(),app.scopes());
                request.setAttribute(ACTOR,actor);
                request.setAttribute("erp.actor.company",company);
                request.setAttribute("erp.actor.tenant",app.tenant());
                request.setAttribute("erp.actor.user",app.user());
                if (!signed.commandKey().isEmpty()) {
                    // 同一幂等标识在不同应用间独立，继续沿用 ERP 原有事务幂等实现。
                    String key=OpenRequest.sha256((app.id()+":"+signed.commandKey()).getBytes(StandardCharsets.UTF_8));
                    request.setAttribute(COMMAND_KEY,key);
                }
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of()));
                SecurityContextHolder.setContext(context);
                if (protectedAction(request.getServletPath())) throw OpenPolicy.denied();
                chain.doFilter(body,response);
            } catch (ApiException denied) {
                if (errors.resolveException(request,response,null,denied)==null) throw denied;
            } finally {
                SecurityContextHolder.setContext(previous);
                if (app!=null) log(app,company,request,response,started);
            }
        }

        private Signed readHeaders(HttpServletRequest request) {
            String key=OpenRequest.header(request,"X-App-Key");
            String timestamp=OpenRequest.header(request,"X-Timestamp");
            String nonce=OpenRequest.header(request,"X-Nonce");
            String signature=OpenRequest.header(request,"X-Signature");
            String company=OpenRequest.header(request,"X-Company-Id");
            String commandKey=OpenRequest.header(request,"Idempotency-Key");
            if (!key.matches("ak_[0-9a-f]{32}") || !timestamp.matches("[1-9][0-9]{0,10}")
                    || !nonce.matches("[A-Za-z0-9]{32}") || !signature.matches("[A-Za-z0-9+/]{43}=")) throw unauthorized();
            long sent=Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond()-sent)>CLOCK_SKEW_SECONDS) throw unauthorized();
            if (!Set.of("GET","POST").contains(request.getMethod())) throw OpenPolicy.denied();
            if (request.getMethod().equals("POST") && !commandKey.matches("[A-Za-z0-9_-]{1,64}")) {
                throw OpenPolicy.invalid("写入请求必须提供合法的幂等键");
            }
            if (request.getMethod().equals("GET") && !commandKey.isEmpty()) throw OpenPolicy.invalid("查询请求不接受幂等键");
            OpenPolicy.id(company);
            var app=apps.key(key);
            if (app==null || !app.active()) throw unauthorized();
            return new Signed(app,timestamp,nonce,company,commandKey,Base64.getDecoder().decode(signature));
        }

        private void verify(OpenRequest request,Signed signed) {
            var app=signed.app();
            String secret=secrets.decrypt(app.tenant(),app.id(),"app",app.cipher());
            byte[] expected=OpenSecrets.sign(secret,request.canonical(signed.timestamp(),signed.nonce(),signed.company(),signed.commandKey()));
            if (!MessageDigest.isEqual(expected,signed.signature())) throw unauthorized();
        }

        private void ensureIp(OpenApplications.App app,String remoteIp) {
            if (app.ipWhitelist().isEmpty()) return;
            try {
                byte[] address=OpenPolicy.address(remoteIp);
                if (app.ipWhitelist().stream().map(OpenPolicy::cidr).anyMatch(cidr -> cidr.contains(address))) return;
            } catch (UnknownHostException ignored) { throw OpenPolicy.denied(); }
            throw OpenPolicy.denied();
        }

        private void reserve(OpenApplications.App app,String nonce,HttpServletResponse response) {
            String prefix="erp:open:{"+app.key()+"}:";
            try {
                Long result=redis.execute(GATE,List.of(prefix+"nonce:"+nonce,prefix+"limit"),Integer.toString(app.limit()),nonce);
                if (result==null) throw unavailable();
                if (result==-1) throw new ApiException(401,"OPEN_REPLAY","请求标识已使用");
                if (result>0) {
                    response.setHeader("Retry-After",result.toString());
                    throw new ApiException(429,"OPEN_RATE_LIMIT","应用调用频率过高，请稍后重试");
                }
            } catch (DataAccessException failure) { throw unavailable(); }
        }

        private void log(OpenApplications.App app,Long company,HttpServletRequest request,HttpServletResponse response,long started) {
            String path=Objects.toString(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),"未匹配路由");
            try { apps.log(app,company,request.getMethod(),path,response.getStatus(),(System.nanoTime()-started)/1_000_000); }
            catch (DataAccessException failure) { LoggerFactory.getLogger(OpenSecurity.class).warn("开放调用日志保存失败"); }
        }
    }

    private static boolean protectedAction(String path) {
        return path.matches(".*/(?:approve|confirm|post|reject|revoke-approval|reverse|sign)(?:/.*)?");
    }

    private static ApiException unauthorized() { return new ApiException(401,"OPEN_AUTH_INVALID","应用凭据、时间戳或签名无效"); }
    private static ApiException unavailable() { return new ApiException(503,"OPEN_SECURITY_UNAVAILABLE","开放平台安全服务暂不可用"); }
}
