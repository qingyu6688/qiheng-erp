package cn.erp.openapi;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import cn.erp.shared.ApiException;
import static org.assertj.core.api.Assertions.*;

class OpenPolicyTest {
    @Test
    void canonicalQueryUsesRfc3986AndRejectsAmbiguousKeys() {
        assertThat(OpenRequest.canonicalQuery("z=%E5%8C%97%E4%BA%AC&a=two+spaces&b=%2f"))
                .isEqualTo("a=two%20spaces&b=%2F&z=%E5%8C%97%E4%BA%AC");
        assertThatThrownBy(() -> OpenRequest.canonicalQuery("x=1&%78=2")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> OpenRequest.canonicalQuery("x=%ff")).isInstanceOf(ApiException.class);
        assertThat(OpenRequest.canonical("POST","/open/v1/sales-orders",null,"1763270400","abc","9","write-1",
                "abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(String.join("\n","POST","/open/v1/sales-orders","",
                    "1763270400","abc","9","write-1","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    }

    @Test
    void webhookEndpointsAndAddressRangesRejectInternalDestinations() throws Exception {
        var transport=new OpenWebhookTransport("receiver.example.com, receiver.example.net:8443");
        assertThat(transport.validate("https://receiver.example.com/hook").getHost()).isEqualTo("receiver.example.com");
        for (String endpoint:java.util.List.of("http://receiver.example.com/","https://receiver.example.com@localhost/",
                "https://receiver.example.com.attacker.example/","https://receiver.example.net/","https://receiver.example.com/#bad")) {
            assertThatThrownBy(() -> transport.validate(endpoint)).isInstanceOf(ApiException.class);
        }
        for (String ip:java.util.List.of("127.0.0.1","10.1.0.1","169.254.169.254","100.64.0.1","192.0.2.1","::1","fe80::1","fc00::1","2001:db8::4")) {
            assertThat(OpenWebhookTransport.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
        }
        assertThat(OpenWebhookTransport.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(OpenPolicy.cidr("192.0.2.128/25").contains(OpenPolicy.address("192.0.2.190"))).isTrue();
        assertThat(OpenPolicy.cidr("192.0.2.128/25").contains(OpenPolicy.address("192.0.2.126"))).isFalse();
    }
}
