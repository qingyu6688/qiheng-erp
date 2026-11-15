package cn.erp.identity;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LoginCodeDispatch {
    private static final Logger LOG = LoggerFactory.getLogger(LoginCodeDispatch.class);
    private static final int DELIVERY_THREADS = 4;
    private static final int QUEUE_SIZE = 32;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(DELIVERY_THREADS, DELIVERY_THREADS, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_SIZE),
            Thread.ofPlatform().daemon().name("erp-login-delivery-", 0).factory());

    void submit(Runnable work) {
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException failure) {
                    LOG.warn("后台登录验证码任务未能完成，异常类型 {}", failure.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException busy) {
            LOG.warn("后台登录验证码队列暂时无法接收任务");
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
