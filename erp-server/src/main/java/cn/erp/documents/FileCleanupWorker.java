package cn.erp.documents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="erp.worker.enabled", havingValue="true", matchIfMissing=true)
public class FileCleanupWorker {
    private static final Logger LOG = LoggerFactory.getLogger(FileCleanupWorker.class);
    private final ObjectCompensation compensation;

    public FileCleanupWorker(ObjectCompensation compensation) { this.compensation = compensation; }

    @Scheduled(fixedDelay=60_000, initialDelay=60_000)
    public void clean() {
        try { compensation.retry(); }
        catch (RuntimeException error) { LOG.warn("私有对象清理任务暂不可用，下轮重试"); }
    }
}
