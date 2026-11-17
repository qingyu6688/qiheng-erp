package cn.erp.einvoice;

import cn.erp.shared.ApiException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class GatewayEInvoiceProvider implements EInvoiceProvider {
    private static final Duration CONNECT_TIMEOUT=Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT=Duration.ofSeconds(8);
    private static final int MAX_RESPONSE=4*1024*1024;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String origin;

    GatewayEInvoiceProvider(ObjectMapper json,@Value("${ERP_EINVOICE_GATEWAY_URL:}") String configured) {
        this.json=json;
        if(configured.isBlank()) {this.origin="";return;}
        var uri=URI.create(configured.strip());
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getQuery()!=null||uri.getFragment()!=null
                ||uri.getUserInfo()!=null||!uri.normalize().equals(uri)) {
            throw new IllegalStateException("数电发票网关须使用受信的固定 HTTPS 地址");
        }
        this.origin=uri.toString().replaceAll("/+$", "");
    }

    @Override public boolean available() {return !origin.isEmpty();}
    @Override public void test(Credentials credentials) {exchange(credentials,"/health",null);}
    @Override public Result query(Credentials credentials,String key) {return result(credentials,"/requests/"+identifier(key),null);}
    @Override public Result issue(Credentials credentials,String key,Issue issue) {return result(credentials,"/issue/"+identifier(key),issue);}
    @Override public Result redIssue(Credentials credentials,String key,Red red) {return result(credentials,"/red-issue/"+identifier(key),red);}
    @Override public byte[] download(Credentials credentials,String number,String format) {
        return exchange(credentials,"/files/"+identifier(number)+"/"+identifier(format),null,true);
    }
    @Override public List<Inbound> downloadInbound(Credentials credentials,LocalDate from,LocalDate to) {
        byte[] response=exchange(credentials,"/inbound?from="+from+"&to="+to,null);
        try {return json.readValue(response,new TypeReference<List<Inbound>>(){});}
        catch(RuntimeException failure) {throw unavailable();}
    }

    private Result result(Credentials credentials,String path,Object body) {
        byte[] response=exchange(credentials,path,body);
        try {
            var result=json.readValue(response,Result.class);
            if(result==null||result.status()==null)throw unavailable();
            return result;
        } catch(RuntimeException failure) {throw unavailable();}
    }

    private byte[] exchange(Credentials credentials,String path,Object body) {
        return exchange(credentials,path,body,false);
    }

    private byte[] exchange(Credentials credentials,String path,Object body,boolean optional) {
        if(!available())throw unavailable();
        var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(REQUEST_TIMEOUT)
                .header("Authorization","Bearer "+credentials.token()).header("X-Seller-Tax-No",credentials.taxNo());
        if(body!=null)builder.header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8));
        var exchange=client.sendAsync(builder.build(),response->HttpResponse.BodySubscribers.fromSubscriber(
                new BoundedResponse(response.statusCode()==200?MAX_RESPONSE:0),bounded->bounded.bytes.toByteArray()));
        try {
            var response=exchange.get(REQUEST_TIMEOUT.toMillis(),TimeUnit.MILLISECONDS);
            if(optional&&response.statusCode()==404)return null;
            if(response.statusCode()!=200)throw unavailable();
            return response.body();
        } catch(InterruptedException interrupted) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch(TimeoutException failure) {
            exchange.cancel(true);
            throw unavailable();
        } catch(ExecutionException failure) {throw unavailable();}
    }

    private String identifier(String value) {
        if(value==null||!value.matches("[A-Za-z0-9_-]{1,64}"))throw unavailable();
        return value;
    }
    private ApiException unavailable() {return new ApiException(503,"EINV_PROVIDER_UNAVAILABLE","数电发票服务商暂不可用，稍后将查询并补偿");}

    private static final class BoundedResponse implements Flow.Subscriber<List<ByteBuffer>> {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;

        private BoundedResponse(int limit) {this.limit=limit;}
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription=subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if(limit>0)for(ByteBuffer buffer:buffers) {
                if(buffer.remaining()>limit-bytes.size()) {
                    subscription.cancel();
                    throw new IllegalStateException("数电发票网关响应超过上限");
                }
                var chunk=new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { /* HTTP 请求的 future 负责转译传输错误。 */ }
        @Override public void onComplete() { /* 响应已保存在有上限的缓冲区中。 */ }
    }
}
