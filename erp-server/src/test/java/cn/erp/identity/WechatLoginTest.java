package cn.erp.identity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.json.JsonMapper;

import cn.erp.shared.ApiException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WechatLoginTest {
    private static final long TENANT = 31L;
    private static final String APP_ID = "wx765432abcdef";
    private static final String CALLBACK = "https://erp.example.test/api/v1/auth/wechat/callback";
    private final SessionAccount account = new SessionAccount(TENANT, 101, 2, System.currentTimeMillis());
    private final LoginSettingsService settings = mock(LoginSettingsService.class);
    private final LoginIdentities identities = mock(LoginIdentities.class);
    private final AuthSessions sessions = mock(AuthSessions.class);
    private final AccessService access = mock(AccessService.class);
    private final WechatOAuthClient oauth = mock(WechatOAuthClient.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final LoginSettingsService.Provider provider = new LoginSettingsService.Provider(
            Map.of("appId", APP_ID, "callbackUrl", CALLBACK), "服务端密钥", 2L);
    private final WechatLoginController controller = new WechatLoginController(settings, identities, sessions,
            access, mock(LoginLimiter.class), oauth, redis, json, mock(LoginAudit.class), "erp:session:wechat-test");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepareStateStore() {
        Map<String, String> states = new HashMap<>();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(call -> {
            assertEquals(Duration.ofMinutes(5), call.getArgument(2));
            states.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.execute(ArgumentMatchers.<RedisScript<String>>any(), anyList())).thenAnswer(call -> {
            List<String> keys = call.getArgument(1);
            return states.remove(keys.getFirst());
        });
        when(identities.tenantId("DEMO")).thenReturn(TENANT);
        when(settings.provider(TENANT, "wechat")).thenReturn(provider);
        when(oauth.openId(provider, "approved-code")).thenReturn("same_openid");
    }

    @Test
    void stateWorksOnlyInTheOriginalBrowserAndOnlyOnce() {
        MockHttpServletRequest original = request(new MockHttpSession());
        MockHttpServletResponse response = new MockHttpServletResponse();
        String authorizationUrl = controller.login(new WechatLoginController.LoginStart("DEMO"), original)
                .data().authorizationUrl();
        assertTrue(authorizationUrl.startsWith("https://open.weixin.qq.com/connect/qrconnect?appid=" + APP_ID));
        assertTrue(authorizationUrl.contains("redirect_uri=https%3A%2F%2Ferp.example.test%2Fapi%2Fv1%2Fauth%2Fwechat%2Fcallback"));
        assertTrue(authorizationUrl.contains("scope=snsapi_login"));
        assertFalse(authorizationUrl.contains("secret"));
        String state = state(authorizationUrl);
        when(identities.authenticate(TENANT, "wechat", APP_ID + ":same_openid")).thenReturn(account);

        assertEquals(URI.create("/login?wechat=error"), controller.callback("approved-code", state,
                request(new MockHttpSession()), new MockHttpServletResponse()).getHeaders().getLocation());
        verifyNoInteractions(oauth, sessions);
        var result = controller.callback("approved-code", state, original, response);
        assertEquals(HttpStatus.SEE_OTHER, result.getStatusCode());
        assertEquals(URI.create("/login?wechat=success"), result.getHeaders().getLocation());
        assertEquals("no-referrer", result.getHeaders().getFirst("Referrer-Policy"));
        verify(sessions).open(account, original, response);

        assertEquals(URI.create("/login?wechat=error"), controller.callback("approved-code", state, original,
                response).getHeaders().getLocation());
        verify(oauth, times(1)).openId(provider, "approved-code");
    }

    @Test
    void changeOfSettingsDuringTheExchangeInvalidatesTheCallback() {
        MockHttpServletRequest browser = request(new MockHttpSession());
        var updated = new LoginSettingsService.Provider(provider.config(), provider.secret(), 3L);
        when(settings.provider(TENANT, "wechat")).thenReturn(provider, provider, updated);
        String state = loginState(browser);

        assertEquals(URI.create("/login?wechat=error"), controller.callback("approved-code", state, browser,
                new MockHttpServletResponse()).getHeaders().getLocation());
        verify(identities, never()).authenticate(anyLong(), anyString(), anyString());
        verifyNoInteractions(sessions);
    }

    @Test
    void anotherCallbackSiteCannotReceiveTheAuthorizationCode() {
        MockHttpServletRequest browser = request(new MockHttpSession());
        var incorrectSite = new LoginSettingsService.Provider(
                Map.of("appId", APP_ID, "callbackUrl", "https://another.example.test/api/v1/auth/wechat/callback"),
                provider.secret(), provider.version());
        when(settings.provider(TENANT, "wechat")).thenReturn(incorrectSite);

        var failure = assertThrows(ApiException.class,
                () -> controller.login(new WechatLoginController.LoginStart("DEMO"), browser));
        assertEquals("WECHAT_CALLBACK_HOST_MISMATCH", failure.getErrorCode());
        verify(redis, never()).opsForValue();
    }

    @Test
    void unboundWechatIdentityCannotOpenAnErpSession() {
        MockHttpServletRequest browser = request(new MockHttpSession());
        String state = loginState(browser);
        when(identities.authenticate(TENANT, "wechat", APP_ID + ":same_openid"))
                .thenThrow(new ApiException(401, "INVALID_CREDENTIALS", "请先绑定微信"));

        assertEquals(URI.create("/login?wechat=error"), controller.callback("approved-code", state, browser,
                new MockHttpServletResponse()).getHeaders().getLocation());
        verify(identities, never()).bind(any(), anyString(), anyString());
        verifyNoInteractions(sessions);
    }

    @Test
    void bindingUsesTheAccountThatReauthenticatedBeforeScanning() {
        MockHttpServletRequest browser = request(new MockHttpSession());
        account.save(browser);
        when(identities.reauthenticate(browser, "当前密码")).thenReturn(account);
        when(access.current(browser, account)).thenReturn(new AccessService.UserView("101", "member", "测试人员",
                false, Set.of(), List.of()));
        String state = bindingState(browser);

        assertEquals(URI.create("/account/login-methods?wechat=bound"), controller.callback("approved-code", state,
                browser, new MockHttpServletResponse()).getHeaders().getLocation());
        verify(identities).bind(account, "wechat", APP_ID + ":same_openid");
        verifyNoInteractions(sessions);
    }

    @Test
    void changingThePasswordBeforeBindingForcesARescan() {
        MockHttpServletRequest browser = request(new MockHttpSession());
        account.save(browser);
        when(identities.reauthenticate(browser, "当前密码")).thenReturn(account);
        String state = bindingState(browser);
        new SessionAccount(TENANT, 101, 3, System.currentTimeMillis()).save(browser);

        assertEquals(URI.create("/account/login-methods?wechat=error"), controller.callback("approved-code", state,
                browser, new MockHttpServletResponse()).getHeaders().getLocation());
        verify(identities, never()).bind(any(), anyString(), anyString());
        verifyNoInteractions(oauth);
    }

    @Test
    void wechatExchangeRequiresAValidOpenIdAndWebsiteScope() {
        var client = new WechatOAuthClient(json);
        assertEquals("test_openid", client.readOpenId(bytes("""
                {"access_token":"provider-token","openid":"test_openid","scope":"snsapi_login"}
                """)));
        assertThrows(ApiException.class, () -> client.readOpenId(bytes("""
                {"errcode":40029,"errmsg":"invalid code"}
                """)));
        assertThrows(ApiException.class, () -> client.readOpenId(bytes("""
                {"access_token":"provider-token","openid":"test_openid","scope":"snsapi_base"}
                """)));
    }

    private static MockHttpServletRequest request(MockHttpSession session) {
        var request = new MockHttpServletRequest();
        request.setSession(session);
        request.setServerName("erp.example.test");
        request.setServerPort(443);
        request.setScheme("https");
        return request;
    }

    private String loginState(MockHttpServletRequest browser) {
        return state(controller.login(new WechatLoginController.LoginStart("DEMO"), browser).data().authorizationUrl());
    }

    private String bindingState(MockHttpServletRequest browser) {
        return state(controller.bind(new WechatLoginController.BindingStart("当前密码"), browser).data().authorizationUrl());
    }

    private static String state(String authorizationUrl) {
        String query = URI.create(authorizationUrl).getRawQuery();
        int index = query.lastIndexOf("&state=");
        assertTrue(index >= 0);
        return query.substring(index + "&state=".length());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
