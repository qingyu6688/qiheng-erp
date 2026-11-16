package cn.erp.shared;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class RequestLoggingTest {
    @TempDir Path directory;
    @Test void unsupportedMethodsCannotPoisonTheBoundedLogColumnOrExposeArbitraryInput() throws Exception {
        var observations=new ArrayList<ApiObservation>();var filter=new RequestIds();filter.setEvents(event->observations.add((ApiObservation)event));
        var request=new MockHttpServletRequest("PRIVATE-METHOD-DO-NOT-LOG","/api/v1/unknown/123");request.setQueryString("secret=private");filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{});
        assertThat(observations).hasSize(1);assertThat(observations.getFirst().method()).isEqualTo("其他");assertThat(observations.getFirst().route()).isEqualTo("未匹配路由").doesNotContain("123");assertThat(observations.toString()).doesNotContain("PRIVATE-METHOD","secret=");
    }

    @Test void concurrentRequestsKeepEveryIdAndStatusWhenAsyncQueueIsSmallerThanTheBurst() throws Exception {
        var context=(LoggerContext)LoggerFactory.getILoggerFactory();var logger=context.getLogger(RequestIds.class);
        var original=new ArrayList<Appender<ILoggingEvent>>();logger.iteratorForAppenders().forEachRemaining(original::add);
        Level level=logger.getLevel();boolean additive=logger.isAdditive();original.forEach(logger::detachAppender);
        var encoder=new PatternLayoutEncoder();encoder.setContext(context);encoder.setPattern("%msg%n");encoder.start();
        var file=new FileAppender<ILoggingEvent>();file.setContext(context);file.setName("请求日志验收文件");file.setFile(directory.resolve("http.log").toString());file.setEncoder(encoder);file.start();
        var async=new AsyncAppender();async.setContext(context);async.setName("请求日志验收队列");async.setQueueSize(8);async.setDiscardingThreshold(0);async.setNeverBlock(false);async.setMaxFlushTime(30000);async.addAppender(file);async.start();
        logger.setLevel(Level.INFO);logger.setAdditive(false);logger.addAppender(async);
        Set<String> ids=ConcurrentHashMap.newKeySet();
        try(var executor=Executors.newFixedThreadPool(20)) {
            var jobs=new ArrayList<Future<?>>();
            for(int index=0;index<1000;index++) {int status=index%2==0?200:409;jobs.add(executor.submit(()->{
                var request=new MockHttpServletRequest("GET","/api/v1/skus");request.setQueryString("token=不能写入日志");
                var response=new MockHttpServletResponse();
                try {new RequestIds().doFilter(request,response,(req,res)->{ids.add(RequestIds.current());response.setStatus(status);});}
                catch(Exception error) {throw new IllegalStateException("并发请求日志验收失败",error);}
                assertThat(RequestIds.current()).isNull();assertThat(response.getHeader("X-Request-Id")).isNotBlank();
            }));}
            for(var job:jobs) {job.get(20,TimeUnit.SECONDS);}
        } finally {
            logger.detachAppender(async);async.stop();file.stop();encoder.stop();logger.setLevel(level);logger.setAdditive(additive);original.forEach(logger::addAppender);
        }
        var lines=Files.readAllLines(directory.resolve("http.log"));
        assertThat(ids).hasSize(1000);assertThat(lines).hasSize(1000).noneMatch(line->line.contains("token="));
        assertThat(lines.stream().filter(line->line.contains("状态 200")).count()).isEqualTo(500);
        assertThat(lines.stream().filter(line->line.contains("状态 409")).count()).isEqualTo(500);
        for(String id:ids) {assertThat(lines.stream().filter(line->line.contains(id)).count()).isEqualTo(1);}
    }
}
