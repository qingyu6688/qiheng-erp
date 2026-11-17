package cn.erp.ai.gateway;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortalIntentControllerTest {
    private final String signingKey=UUID.randomUUID().toString();
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ModelGateway gateway=mock(ModelGateway.class);
    private final PortalIntentController controller=new PortalIntentController(jdbc,gateway,JsonMapper.builder().build(),signingKey);
    private final byte[] body="""
            {"tenantId":"77","companyId":"93","userId":"101","question":"看看本月发票"}
            """.getBytes(StandardCharsets.UTF_8);

    @Test
    void signedPortalQuestionUsesDedicatedRouteAndDoesNotSupplyIdentityToModel() throws Exception {
        var timestamp=Long.toString(Instant.now().getEpochSecond());
        var nonce=nonce();
        when(jdbc.update(contains("INSERT IGNORE"),eq(nonce))).thenReturn(1);
        when(gateway.prioritizeExplanation(any(),eq("customer_service"),anyString(),anyString(),any()))
                .thenReturn(new ModelResult("{\"topic\":\"invoices\"}","1",12,4));
        assertThat(controller.classify(timestamp,nonce,sign(timestamp,nonce,body),body).data()).containsEntry("topic","invoices");
        var actor=ArgumentCaptor.forClass(AiActor.class);
        var text=ArgumentCaptor.forClass(String.class);
        verify(gateway).prioritizeExplanation(actor.capture(),eq("customer_service"),anyString(),text.capture(),any());
        assertThat(actor.getValue().tenantId()).isEqualTo("77");
        assertThat(actor.getValue().companyId()).isEqualTo("93");
        assertThat(text.getValue()).contains("本月发票").doesNotContain("tenantId","companyId","userId");
    }

    @Test
    void forgedBodyAndOldSignatureNeverReachModel() throws Exception {
        var nonce=nonce();
        var timestamp=Long.toString(Instant.now().getEpochSecond());
        var obsolete=Long.toString(Instant.now().minusSeconds(90).getEpochSecond());
        var signature=sign(timestamp,nonce,body);
        var tampered=new String(body,StandardCharsets.UTF_8).replace("77","78").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->controller.classify(timestamp,nonce,signature,tampered)).isInstanceOf(AiException.class);
        assertThatThrownBy(()->controller.classify(obsolete,nonce,sign(obsolete,nonce,body),body)).isInstanceOf(AiException.class);
        verifyNoInteractions(jdbc,gateway);
    }

    @Test
    void replayIsDeniedAndUnexpectedModelOutputOnlyOffersHumanHandoff() throws Exception {
        var timestamp=Long.toString(Instant.now().getEpochSecond());
        var nonce=nonce();
        var signature=sign(timestamp,nonce,body);
        when(jdbc.update(contains("INSERT IGNORE"),eq(nonce))).thenReturn(1,0);
        when(gateway.prioritizeExplanation(any(),eq("customer_service"),anyString(),anyString(),any()))
                .thenReturn(new ModelResult("{\"topic\":\"delete_order\",\"url\":\"//other-host\"}","1",10,1));
        assertThat(controller.classify(timestamp,nonce,signature,body).data()).containsEntry("topic","handoff");
        assertThatThrownBy(()->controller.classify(timestamp,nonce,signature,body)).isInstanceOf(AiException.class);
        verify(gateway,times(1)).prioritizeExplanation(any(),eq("customer_service"),anyString(),anyString(),any());
    }

    private static String nonce() {return UUID.randomUUID().toString().replace("-","");}

    private String sign(String timestamp,String nonce,byte[] value) throws Exception {
        var mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update(("portal-intent-v1\n"+timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(value));
    }
}
