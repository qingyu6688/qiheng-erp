package cn.erp.shared;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class CreatedLocationsTest {
    @Test
    void creationResponsesLinkToTheNewResourceAndKeepExplicitLocation() {
        var advice=new CreatedLocations(new ObjectMapper());
        var request=new ServletServerHttpRequest(new MockHttpServletRequest("POST","/api/v1/partners"));
        var rawResponse=new MockHttpServletResponse();
        var response=new ServletServerHttpResponse(rawResponse);
        var body=ApiResponse.created(Map.of("id","123"));
        assertThat(advice.beforeBodyWrite(body,null,MediaType.APPLICATION_JSON,null,request,response)).isSameAs(body);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/v1/partners/123");
        response.getHeaders().set("Location","/api/v1/custom/123");
        advice.beforeBodyWrite(body,null,MediaType.APPLICATION_JSON,null,request,response);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/v1/custom/123");
    }
}
