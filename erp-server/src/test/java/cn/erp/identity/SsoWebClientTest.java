package cn.erp.identity;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsoWebClientTest {
    private static final String ISSUER="https://idp.example.test/realm/test";
    private static final String CLIENT="qiheng-fixture";
    private static final String NONCE="nonce-fixture";

    @Test
    void validatesSignedOidcProofAndExactNonceAudienceExpiry() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var trusted=generator.generateKeyPair();
        var foreign=generator.generateKeyPair();
        var decoder=NimbusJwtDecoder.withPublicKey((RSAPublicKey)trusted.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        Instant now=Instant.now();
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).subject("external-user").audience(CLIENT)
                .issueTime(Date.from(now.minusSeconds(2))).expirationTime(Date.from(now.plusSeconds(60)))
                .claim("nonce",NONCE).claim("email","user@example.test").claim("email_verified",true).build();
        Jwt signed=decoder.decode(sign(claims,(RSAPrivateKey)trusted.getPrivate()));
        assertThat(SsoWebClient.oidcIdentity(signed,ISSUER,CLIENT,NONCE,now))
                .isEqualTo(new SsoWebClient.Identity(ISSUER+"|external-user","user@example.test"));
        assertThatThrownBy(() -> decoder.decode(sign(claims,(RSAPrivateKey)foreign.getPrivate()))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> SsoWebClient.oidcIdentity(signed,ISSUER,CLIENT,"foreign-nonce",now)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsoWebClient.oidcIdentity(signed,ISSUER,"foreign-client",NONCE,now)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SsoWebClient.oidcIdentity(signed,ISSUER,CLIENT,NONCE,now.plusSeconds(70))).isInstanceOf(ApiException.class);
        var multiple=decoder.decode(sign(new JWTClaimsSet.Builder(claims).audience(List.of(CLIENT,"other")).build(),
                (RSAPrivateKey)trusted.getPrivate()));
        assertThatThrownBy(() -> SsoWebClient.oidcIdentity(multiple,ISSUER,CLIENT,NONCE,now)).isInstanceOf(ApiException.class);
        var unverified=decoder.decode(sign(new JWTClaimsSet.Builder(claims).claim("email_verified","true").build(),
                (RSAPrivateKey)trusted.getPrivate()));
        assertThat(SsoWebClient.oidcIdentity(unverified,ISSUER,CLIENT,NONCE,now).verifiedEmail()).isNull();
    }

    @Test
    void providerAuthorizeUrlsUseFixedVendorsAndPreserveState() {
        var client=new SsoWebClient(new ObjectMapper());
        var config=Map.of("callbackUrl","https://erp.example.test/api/v1/auth/sso/callback","clientId",CLIENT,
                "corpId","ww-fixture","agentId","100001","authorizationUrl",ISSUER+"/authorize");
        assertThat(client.authorize(new SsoSettings.Provider("oidc",config,"",1),"state",NONCE,"challenge").toString())
                .startsWith(ISSUER).contains("state=state","nonce="+NONCE,"code_challenge_method=S256","code_challenge=challenge");
        assertThat(client.authorize(new SsoSettings.Provider("wecom",config,"",1),"state",NONCE,"challenge").toString())
                .startsWith("https://login.work.weixin.qq.com/").contains("login_type=CorpApp","state=state","agentid=100001");
        assertThat(client.authorize(new SsoSettings.Provider("dingtalk",config,"",1),"state",NONCE,"challenge").toString())
                .startsWith("https://login.dingtalk.com/").contains("state=state","scope=openid");
        assertThat(client.authorize(new SsoSettings.Provider("feishu",config,"",1),"state",NONCE,"challenge").toString())
                .startsWith("https://accounts.feishu.cn/").contains("state=state","code_challenge_method=S256");
    }

    private String sign(JWTClaimsSet claims,RSAPrivateKey signingKey) throws Exception {
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
}
