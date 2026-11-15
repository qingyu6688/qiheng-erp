package cn.erp.identity;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;

@Component
class WechatOAuthClient {
    private static final String SCAN_URL = "https://open.weixin.qq.com/connect/qrconnect";
    private static final String TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final String LOGIN_SCOPE = "snsapi_login";
    private static final int TIMEOUT_MILLIS = 5_000;
    private static final int MAX_RESPONSE_BYTES = 16_384;
    private static final Pattern OPEN_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final ObjectMapper json;

    WechatOAuthClient(ObjectMapper json) {
        this.json = json;
    }

    static String authorizationUrl(LoginSettingsService.Provider provider, String state) {
        return SCAN_URL + "?appid=" + encode(provider.config().get("appId"))
                + "&redirect_uri=" + encode(provider.config().get("callbackUrl"))
                + "&response_type=code&scope=" + LOGIN_SCOPE + "&state=" + encode(state) + "#wechat_redirect";
    }

    String openId(LoginSettingsService.Provider provider, String code) {
        String endpoint = TOKEN_URL + "?appid=" + encode(provider.config().get("appId"))
                + "&secret=" + encode(provider.secret()) + "&code=" + encode(code) + "&grant_type=authorization_code";
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != 200) throw unavailable();
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw unavailable();
                return readOpenId(bytes);
            }
        } catch (IOException failure) {
            // 微信接口的查询参数含密钥，响应内容和原始网络异常均不回传。
            throw unavailable();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    String readOpenId(byte[] bytes) {
        var result = json.readTree(bytes);
        if (result == null || !result.isObject()) throw unavailable();
        if (result.hasNonNull("errcode") && result.path("errcode").asInt(-1) != 0) throw unavailable();
        String openId = result.path("openid").asString("");
        String scope = result.path("scope").asString("");
        if (!OPEN_ID.matcher(openId).matches() || result.path("access_token").asString("").isBlank()
                || !List.of(scope.split("[,\\s]+")).contains(LOGIN_SCOPE)) {
            throw unavailable();
        }
        return openId;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static ApiException unavailable() {
        return new ApiException(502, "WECHAT_AUTH_FAILED", "微信授权未完成，请重新扫码");
    }
}
