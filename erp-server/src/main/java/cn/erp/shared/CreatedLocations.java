package cn.erp.shared;

import java.net.URI;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.ObjectMapper;

@RestControllerAdvice
public class CreatedLocations implements ResponseBodyAdvice<Object> {
    private final ObjectMapper json;

    public CreatedLocations(ObjectMapper json) {this.json=json;}

    @Override
    public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,MethodParameter method,MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response) {
        if(body instanceof ApiResponse<?> api && api.code()==201 && api.data()!=null && response.getHeaders().getLocation()==null) {
            var id=json.valueToTree(api.data()).get("id");
            if(id!=null && id.isTextual() && id.asText().matches("[1-9][0-9]*")) {
                response.getHeaders().setLocation(URI.create(request.getURI().getRawPath()+"/"+id.asText()));
            }
        }
        return body;
    }
}
