package cn.erp.notify;

import cn.erp.shared.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class RobotClientTest {
    @Test void robotUsesOfficialShapeAndOnlySendsGenericReminderToMockEndpoint() throws Exception {
        var body=new AtomicReference<String>();var query=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/webhook",exchange->{query.set(exchange.getRequestURI().getQuery());body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] response="{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
        try {var json=new ObjectMapper();var client=new RobotClient(json,HttpClient.newHttpClient(),"http://127.0.0.1:"+server.getAddress().getPort()+"/webhook");client.send("mock-test-key");assertThat(query.get()).isEqualTo("key=mock-test-key");var data=json.readTree(body.get());assertThat(data.path("msgtype").asString()).isEqualTo("text");assertThat(data.path("text").path("content").asString()).contains("登录工作台").doesNotContain("金额","客户","单号");}
        finally {server.stop(0);}
    }
    @Test void robotDoesNotFollowRedirectOrExposeProviderResponse() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/webhook",exchange->{exchange.getResponseHeaders().add("Location","http://127.0.0.1/禁止重定向");exchange.sendResponseHeaders(302,-1);exchange.close();});server.start();
        try {var client=new RobotClient(new ObjectMapper(),HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),"http://127.0.0.1:"+server.getAddress().getPort()+"/webhook");assertThatThrownBy(()->client.send("mock-key")).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getMessage()).doesNotContain("mock-key","Location"));} finally {server.stop(0);}
    }
}
