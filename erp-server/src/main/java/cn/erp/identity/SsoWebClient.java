package cn.erp.identity;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;

@Component
class SsoWebClient {
    private static final String WECOM_API="https://qyapi.weixin.qq.com/cgi-bin/";
    private static final String DING_API="https://api.dingtalk.com/v1.0/";
    private static final String FEISHU_API="https://open.feishu.cn/open-apis/";
    private static final String FEISHU_TOKEN="https://accounts.feishu.cn/oauth/v3/token";
    private static final int HTTP_TIMEOUT_MS=5000;
    private static final int MAX_RESPONSE_BYTES=131_072;
    private final ObjectMapper json;
    private final RestTemplate jwksRequests;

    SsoWebClient(ObjectMapper json) {
        this.json=json;
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        var factory=new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.jwksRequests=new RestTemplate(factory);
    }

    URI authorize(SsoSettings.Provider provider,String state,String nonce,String pkce) {
        var config=provider.config();
        String callback=config.get("callbackUrl");
        return switch(provider.type()) {
            case "oidc" -> query(config.get("authorizationUrl"),Map.of("client_id",config.get("clientId"),
                    "response_type","code","scope","openid email","redirect_uri",callback,"state",state,
                    "nonce",nonce,"code_challenge",pkce,"code_challenge_method","S256"));
            case "wecom" -> query("https://login.work.weixin.qq.com/wwlogin/sso/login",Map.of("login_type","CorpApp",
                    "appid",config.get("corpId"),"agentid",config.get("agentId"),"redirect_uri",callback,"state",state));
            case "dingtalk" -> query("https://login.dingtalk.com/oauth2/auth",Map.of("client_id",config.get("clientId"),
                    "response_type","code","scope","openid","prompt","consent","redirect_uri",callback,"state",state));
            case "feishu" -> query("https://accounts.feishu.cn/open-apis/authen/v1/authorize",Map.of("client_id",config.get("clientId"),
                    "response_type","code","redirect_uri",callback,"state",state,"code_challenge",pkce,"code_challenge_method","S256"));
            default -> throw SsoSettings.disabled();
        };
    }

    Identity exchange(SsoSettings.Provider provider,String code,String nonce,String verifier) {
        return switch(provider.type()) {
            case "oidc" -> oidc(provider,code,nonce,verifier);
            case "wecom" -> wecom(provider,code);
            case "dingtalk" -> dingtalk(provider,code);
            case "feishu" -> feishu(provider,code,verifier);
            default -> throw SsoSettings.disabled();
        };
    }

    void test(SsoSettings.Provider provider) {
        String clientId=provider.config().get("clientId");
        switch(provider.type()) {
            case "oidc" -> {
                JsonNode keys=exchange(URI.create(provider.config().get("jwksUrl")),Map.of(),null,null).path("keys");
                if (!keys.isArray() || keys.isEmpty()) throw unavailable();
            }
            case "wecom" -> wecomToken(provider);
            case "dingtalk" -> token(post(DING_API+"oauth2/accessToken",Map.of("appKey",clientId,
                    "appSecret",provider.secret())),"accessToken");
            case "feishu" -> token(post(FEISHU_API+"auth/v3/tenant_access_token/internal",Map.of("app_id",clientId,
                    "app_secret",provider.secret())),"tenant_access_token");
            default -> throw SsoSettings.disabled();
        }
    }

    private Identity oidc(SsoSettings.Provider provider,String code,String nonce,String verifier) {
        var config=provider.config();
        String id=config.get("clientId");
        var form=new LinkedHashMap<>(Map.of("grant_type","authorization_code","redirect_uri",config.get("callbackUrl"),
                "code",code,"code_verifier",verifier));
        var headers=new LinkedHashMap<String,String>();
        if ("client_secret_post".equals(config.get("clientAuth"))) {
            form.put("client_id",id);
            form.put("client_secret",provider.secret());
        } else {
            String pair=encode(id)+":"+encode(provider.secret());
            headers.put("Authorization","Basic "+Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.US_ASCII)));
        }
        var result=exchange(URI.create(config.get("tokenUrl")),headers,urlFields(form).getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded");
        String idToken=scalar(result,"id_token",16_384);
        try {
            var decoder=NimbusJwtDecoder.withJwkSetUri(config.get("jwksUrl")).jwsAlgorithm(SignatureAlgorithm.RS256)
                    .restOperations(jwksRequests).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(config.get("issuer")));
            return oidcIdentity(decoder.decode(idToken),config.get("issuer"),id,nonce,Instant.now());
        } catch(JwtException | IllegalArgumentException failure) {
            throw LoginIdentities.authFailed();
        }
    }

    static Identity oidcIdentity(Jwt token,String issuer,String clientId,String nonce,Instant now) {
        List<String> audience=token.getAudience();
        Object presenter=token.getClaims().get("azp");
        if (token.getIssuer()==null || !issuer.equals(token.getIssuer().toString()) || audience==null || !audience.contains(clientId)
                || (audience.size()>1 || presenter!=null) && !clientId.equals(presenter)
                || token.getIssuedAt()==null || token.getIssuedAt().isAfter(now.plusSeconds(60))
                || token.getExpiresAt()==null || !token.getExpiresAt().isAfter(now)
                || !nonce.equals(token.getClaims().get("nonce"))) throw LoginIdentities.authFailed();
        String subject=identifier(token.getSubject());
        String email=token.getClaims().get("email") instanceof String value?value:null;
        boolean verified=Boolean.TRUE.equals(token.getClaims().get("email_verified")) && email!=null && LoginSettingsService.validAddress(email);
        return new Identity(issuer+"|"+subject,verified?email:null);
    }

    private Identity wecom(SsoSettings.Provider provider,String code) {
        String access=wecomToken(provider);
        var result=exchange(query(WECOM_API+"auth/getuserinfo",Map.of("access_token",access,"code",code)),Map.of(),null,null);
        String member=result.path("userid").asString(result.path("UserId").asString(""));
        return new Identity(provider.config().get("corpId")+"|"+identifier(member),null);
    }

    private String wecomToken(SsoSettings.Provider provider) {
        return token(exchange(query(WECOM_API+"gettoken",Map.of("corpid",provider.config().get("corpId"),
                "corpsecret",provider.secret())),Map.of(),null,null),"access_token");
    }

    private Identity dingtalk(SsoSettings.Provider provider,String code) {
        String clientId=provider.config().get("clientId");
        String access=token(post(DING_API+"oauth2/userAccessToken",Map.of("clientId",clientId,"clientSecret",provider.secret(),
                "code",code,"grantType","authorization_code")),"accessToken");
        var result=exchange(URI.create(DING_API+"contact/users/me"),Map.of("x-acs-dingtalk-access-token",access),null,null);
        return new Identity(clientId+"|"+identifier(scalar(result,"unionId",255)),null);
    }

    private Identity feishu(SsoSettings.Provider provider,String code,String verifier) {
        String clientId=provider.config().get("clientId");
        String access=token(post(FEISHU_TOKEN,Map.of("grant_type","authorization_code","client_id",clientId,
                "client_secret",provider.secret(),"code",code,"code_verifier",verifier,
                "redirect_uri",provider.config().get("callbackUrl"))),"access_token");
        var result=exchange(URI.create(FEISHU_API+"authen/v1/user_info"),Map.of("Authorization","Bearer "+access),null,null);
        return new Identity(clientId+"|"+identifier(scalar(result.path("data"),"open_id",255)),null);
    }

    private JsonNode post(String url,Map<String,String> data) {
        return exchange(URI.create(url),Map.of(),json.writeValueAsBytes(data),"application/json");
    }

    private JsonNode exchange(URI url,Map<String,String> headers,byte[] body,String contentType) {
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)url.toURL().openConnection();
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","application/json");
            headers.forEach(connection::setRequestProperty);
            if (body!=null) {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type",contentType);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (var output=connection.getOutputStream()) {output.write(body);}
            }
            if (connection.getResponseCode()!=200) throw unavailable();
            try (var stream=connection.getInputStream()) {
                byte[] bytes=stream.readNBytes(MAX_RESPONSE_BYTES+1);
                if (bytes.length>MAX_RESPONSE_BYTES) throw unavailable();
                var result=json.readTree(bytes);
                if (!result.isObject() || result.has("errcode") && result.path("errcode").asInt(-1)!=0
                        || result.has("code") && result.path("code").asInt(-1)!=0) throw unavailable();
                return result;
            }
        } catch(IOException | JacksonException | IllegalArgumentException failure) {
            // 部分上游协议把凭据放在查询参数中，不保留或回传原始异常。
            throw unavailable();
        } finally {
            if (connection!=null) connection.disconnect();
        }
    }

    private static String token(JsonNode result,String name) {return scalar(result,name,4096);}

    private static String scalar(JsonNode result,String name,int max) {
        var value=result.path(name);
        String text=value.isString()?value.asString():"";
        if (text.isBlank() || text.length()>max || !text.chars().allMatch(c -> c>=33 && c<=126)) throw unavailable();
        return text;
    }

    private static String identifier(String value) {
        if (value==null || value.isBlank() || value.length()>255 || value.contains("|")
                || !value.chars().allMatch(c -> c>=33 && c<=126)) throw LoginIdentities.authFailed();
        return value;
    }

    static URI query(String url,Map<String,String> values) {return URI.create(url+"?"+urlFields(values));}
    private static String urlFields(Map<String,String> values) {
        return values.entrySet().stream().map(item -> encode(item.getKey())+"="+encode(item.getValue()))
                .collect(Collectors.joining("&"));
    }
    private static String encode(String raw) {return URLEncoder.encode(raw,StandardCharsets.UTF_8);}
    private static ApiException unavailable() {return new ApiException(502,"SSO_PROVIDER_UNAVAILABLE","登录服务请求失败，请重试或联系管理员");}

    record Identity(String subject,String verifiedEmail) {
        @Override public String toString() {return "外部身份验证结果";}
    }
}
