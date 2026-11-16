package cn.erp.ar;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import cn.erp.shared.ManagedJobs;

@Component
@ConditionalOnProperty(name = "erp.worker.enabled", havingValue = "true", matchIfMissing = true)
class CollectionWorker {
    CollectionWorker(ManagedJobs jobs, CollectionService service) {
        jobs.register(
                "ar.collection_remind", tenant -> service.remind(tenant == 0 ? null : tenant));
    }
}
