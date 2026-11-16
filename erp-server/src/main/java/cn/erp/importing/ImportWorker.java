package cn.erp.importing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="erp.worker.enabled",havingValue="true",matchIfMissing=true)
class ImportWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ImportWorker.class);
    private final ImportProcessor processor;
    ImportWorker(ImportProcessor processor){this.processor=processor;}
    @org.springframework.beans.factory.annotation.Autowired
    void managed(cn.erp.shared.ManagedJobs jobs) {jobs.register("import.poll",tenant->processor.processTenant(tenant));}
    public void poll(){try{processor.processNext();}catch(RuntimeException error){LOG.warn("导入任务暂不可用，将在下一轮重试");}}
}
