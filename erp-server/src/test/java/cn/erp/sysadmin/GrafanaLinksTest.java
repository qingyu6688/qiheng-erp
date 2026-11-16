package cn.erp.sysadmin;

import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class GrafanaLinksTest {
    @Test void linkUsesProvisionedLokiAndEncodedRequestIdWithoutChangingGrafanaSubpath() {
        var json=new ObjectMapper();String id="12345678-1234-1234-1234-123456789abc";String value=GrafanaLinks.build("https://grafana.example.invalid/erp/",id,json);
        assertThat(value).startsWith("https://grafana.example.invalid/erp/explore?schemaVersion=1&orgId=1&panes=");String encoded=URI.create(value).getRawQuery().split("panes=",2)[1];var panes=json.readTree(URLDecoder.decode(encoded,StandardCharsets.UTF_8));assertThat(panes.path("a").path("datasource").asString()).isEqualTo("loki");assertThat(panes.path("a").path("queries").get(0).path("expr").asString()).isEqualTo("{job=\"erp\"} |= \""+id+"\"");
    }
    @Test void credentialsScriptsAndArbitraryLogQueriesCannotEnterLinks() {
        var json=new ObjectMapper();for(String value:java.util.List.of("javascript:alert(1)","https://user:password@example.invalid","https://example.invalid/?token=private","https://example.invalid/#secret","")) assertThat(GrafanaLinks.build(value,"abc",json)).isNull();assertThat(GrafanaLinks.build("https://example.invalid","\" OR 1=1",json)).isNull();
    }
}
