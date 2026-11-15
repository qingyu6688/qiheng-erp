package cn.erp.identity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;

@RestController
class WechatLoginController {
    private static final Logger LOG = LoggerFactory.getLogger(WechatLoginController.class);
    private static final String METHOD = "wechat";
    private static final String LOGIN_PAGE = "/login";
    private static final String BINDING_PAGE = "/account/login-methods";
    private static final String SESSION_MARKER = WechatLoginController.class.getName();
    private static final Duration STATE_TTL = Duration.ofMinutes(5);
    private static final Pattern STATE_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<String> TAKE_STATE = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if value then redis.call('DEL', KEYS[1]) end
            return value
            """, String.class);

    private final LoginSettingsService settings;
    private final LoginIdentities identities;
    private final AuthSessions sessions;
    private final AccessService access;
    private final LoginLimiter limiter;
    private final WechatOAuthClient oauth;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String keyPrefix;

    WechatLoginController(LoginSettingsService settings, LoginIdentities identities, AuthSessions sessions,
                          AccessService access, LoginLimiter limiter, WechatOAuthClient oauth,
                          StringRedisTemplate redis, ObjectMapper json,
                          @Value("${spring.session.redis.namespace:erp:session:prod}") String namespace) {
        this.settings = settings;
        this.identities = identities;
        this.sessions = sessions;
        this.access = access;
        this.limiter = limiter;
        this.oauth = oauth;
        this.redis = redis;
        this.json = json;
        this.keyPrefix = namespace + ":wechat-state:";
    }

    @PostMapping(value = IdentitySecurity.AUTH + "/wechat/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Authorization> login(@Valid @RequestBody LoginStart body, HttpServletRequest request) {
        limiter.check(request.getRemoteAddr(), body.tenantCode(), "wechat-login");
        Long tenantId = identities.tenantId(body.tenantCode());
        if (tenantId == null) throw unavailable();
        return begin(request, tenantId, null);
    }

    @PostMapping(value = "/api/v1/account/login-methods/wechat/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Authorization> bind(@Valid @RequestBody BindingStart body, HttpServletRequest request) {
        SessionAccount actor = identities.reauthenticate(request, body.password());
        return begin(request, actor.tenantId(), actor);
    }

    @GetMapping(IdentitySecurity.AUTH + "/wechat/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                  HttpServletRequest request, HttpServletResponse response) {
        String page = LOGIN_PAGE;
        try {
            Attempt attempt = take(request, state);
            if (attempt == null) return redirect(page, "error");
            if (attempt.binding()) page = BINDING_PAGE;
            if (code == null || code.isBlank() || code.length() > 512 || code.chars().anyMatch(Character::isISOControl)) {
                return redirect(page, "error");
            }
            var provider = requireProvider(attempt, request);
            SessionAccount actor = attempt.binding() ? requireBindingActor(request, attempt) : null;
            String subject = provider.config().get("appId") + ":" + oauth.openId(provider, code);
            requireProvider(attempt, request);
            if (actor != null) {
                if (access.current(request, actor).mustChangePassword()) throw SessionAccount.expired();
                identities.bind(actor, METHOD, subject);
                return redirect(page, "bound");
            }
            sessions.open(identities.authenticate(attempt.tenantId(), METHOD, subject), request, response);
            return redirect(page, "success");
        } catch (ApiException failure) {
            if (failure.getStatus() >= 500) LOG.warn("微信回调未完成，错误码 {}", failure.getErrorCode());
            return redirect(page, "error");
        } catch (DataAccessException | JacksonException failure) {
            // 回调地址含授权码，会话和第三方响应也不进入应用日志。
            LOG.warn("微信回调未完成，异常类型 {}", failure.getClass().getSimpleName());
            return redirect(page, "error");
        }
    }

    private ApiResponse<Authorization> begin(HttpServletRequest request, long tenantId, SessionAccount actor) {
        var provider = settings.provider(tenantId, METHOD);
        if (provider == null) throw unavailable();
        requireSameSite(provider, request);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var browser = request.getSession(true);
        // 确保首次授权产生浏览器会话 Cookie，跨站顶层 GET 回调会携带它。
        browser.setAttribute(SESSION_MARKER, Boolean.TRUE);
        var attempt = new Attempt(tenantId, provider.version(), actor == null ? 0 : actor.userId(),
                actor == null ? 0 : actor.passwordRevision());
        redis.opsForValue().set(stateKey(browser.getId(), state), json.writeValueAsString(attempt), STATE_TTL);
        return ApiResponse.success(new Authorization(WechatOAuthClient.authorizationUrl(provider, state)));
    }

    private Attempt take(HttpServletRequest request, String state) {
        var browser = request.getSession(false);
        if (browser == null || state == null || !STATE_FORMAT.matcher(state).matches()) return null;
        // Redis 键包含会话摘要，其他浏览器既无法使用，也无法消耗本次授权。
        String value = redis.execute(TAKE_STATE, List.of(stateKey(browser.getId(), state)));
        return value == null ? null : json.readValue(value, Attempt.class);
    }

    private String stateKey(String sessionId, String state) {
        try {
            byte[] data = (sessionId + "\n" + state).getBytes(StandardCharsets.UTF_8);
            return keyPrefix + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前环境缺少微信会话摘要算法", failure);
        }
    }

    private LoginSettingsService.Provider requireProvider(Attempt attempt, HttpServletRequest request) {
        var provider = settings.provider(attempt.tenantId(), METHOD);
        if (provider == null || provider.version() != attempt.providerVersion()) throw unavailable();
        requireSameSite(provider, request);
        return provider;
    }

    private static void requireSameSite(LoginSettingsService.Provider provider, HttpServletRequest request) {
        String hostHeader = request.getHeader("Host");
        try {
            URI callback = URI.create(provider.config().getOrDefault("callbackUrl", ""));
            URI origin = URI.create("https://" + (hostHeader == null ? request.getServerName() : hostHeader));
            // OAuth 回调与发起页面使用同一 HTTPS 站点，避免把授权码交给其他站点。
            if ("https".equalsIgnoreCase(callback.getScheme()) && callback.getHost() != null
                    && callback.getHost().equalsIgnoreCase(origin.getHost())
                    && callback.getHost().equalsIgnoreCase(request.getServerName())
                    && httpsPort(callback) == httpsPort(origin)) {
                return;
            }
        } catch (IllegalArgumentException failure) {
            // Host 由请求提供，格式无效时使用同一安全提示。
            LOG.debug("微信授权地址格式校验失败");
        }
        throw new ApiException(422, "WECHAT_CALLBACK_HOST_MISMATCH", "微信回调地址必须与当前站点的 HTTPS 地址一致");
    }

    private static int httpsPort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    private static SessionAccount requireBindingActor(HttpServletRequest request, Attempt attempt) {
        SessionAccount actor = SessionAccount.read(request);
        if (actor.tenantId() != attempt.tenantId() || actor.userId() != attempt.userId()
                || actor.passwordRevision() != attempt.passwordRevision()) {
            throw SessionAccount.expired();
        }
        return actor;
    }

    private static ApiException unavailable() {
        return new ApiException(404, "LOGIN_METHOD_DISABLED", "当前企业未启用微信登录，请联系管理员");
    }

    private static ResponseEntity<Void> redirect(String page, String result) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(page + "?wechat=" + result))
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").build();
    }

    record LoginStart(@NotBlank(message = "请输入企业编码") @Size(max = 32, message = "企业编码过长") String tenantCode) {}
    record BindingStart(@NotBlank(message = "请输入当前密码") @Size(max = 256, message = "密码过长") String password) {}
    record Authorization(String authorizationUrl) {}

    private record Attempt(long tenantId, long providerVersion, long userId, long passwordRevision) {
        boolean binding() { return userId != 0; }
    }
}
