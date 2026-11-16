package cn.erp.notify;

import cn.erp.shared.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class RobotClient {
    private final HttpClient http;private final ObjectMapper json;private final String endpoint;
    @org.springframework.beans.factory.annotation.Autowired
    public RobotClient(ObjectMapper json) {this(json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),"https://qyapi.weixin.qq.com/cgi-bin/webhook/send");}
    RobotClient(ObjectMapper json,HttpClient http,String endpoint) {this.json=json;this.http=http;this.endpoint=endpoint;}
    public void send(String key) {
        var request=HttpRequest.newBuilder(URI.create(endpoint+"?key="+key)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("msgtype","text","text",Map.of("content","柒衡 ERP 有新的业务待办，请登录工作台查看。"))),StandardCharsets.UTF_8)).build();
        try {var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));if(response.statusCode()!=200||json.readTree(response.body()).path("errcode").asInt(-1)!=0) throw failure();}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw failure();}
        catch(IOException|tools.jackson.core.JacksonException error) {throw failure();}
    }
    private static ApiException failure() {return new ApiException(503,"ROBOT_DELIVERY_FAILED","企业微信机器人暂时无法送达，请检查发送日志及后台密钥配置");}
}
