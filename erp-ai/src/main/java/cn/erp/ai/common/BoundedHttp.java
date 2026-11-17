package cn.erp.ai.common;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

import org.springframework.stereotype.Component;

@Component
public class BoundedHttp {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();

    public HttpResponse<byte[]> send(HttpRequest request, int limit, Duration timeout) {
        var future = client.sendAsync(request, response -> new LimitedBody(limit));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AiException(503, "AI_INTERRUPTED", "请求已中断");
        } catch (ExecutionException | TimeoutException error) {
            future.cancel(true);
            throw new AiException(502, "AI_UPSTREAM_UNAVAILABLE", "依赖服务暂不可用");
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private long count;
        private Subscription subscription;

        LimitedBody(int limit) { this.limit = limit; }

        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Subscription supplied) {
            subscription = supplied;
            delegate.onSubscribe(supplied);
        }
        @Override public void onNext(List<ByteBuffer> data) {
            for (ByteBuffer buffer : data) count += buffer.remaining();
            if (count > limit) {
                subscription.cancel();
                delegate.onError(new IllegalStateException("上游响应超限"));
                return;
            }
            delegate.onNext(data);
        }
        @Override public void onError(Throwable failure) { delegate.onError(failure); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
}
