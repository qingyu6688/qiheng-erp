package cn.erp.quotation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import cn.erp.shared.ManagedJobs;

/** 沿用统一任务的批次、重试及运行记录，单次只保留200个到期目标。 */
@Component
final class SalesQuoteExpiryJob {
    private final JdbcTemplate jdbc;
    private final SalesQuoteService service;
    SalesQuoteExpiryJob(JdbcTemplate jdbc,SalesQuoteService service,ManagedJobs jobs) {
        this.jdbc=jdbc;this.service=service;jobs.registerBatch("sales.quote-expiry",this::run);
    }
    private void run(long tenant,ManagedJobs.Batch batch) {
        long cursor=0;
        while(true) {
            var rows=jdbc.queryForList("SELECT id,company_id FROM sales_quote WHERE tenant_id=? AND id>? AND valid_to<?"
                    +" AND status IN('draft','pending','issued','rejected') ORDER BY id LIMIT 200",tenant,cursor,SalesQuoteQueries.today());
            if(rows.isEmpty())return;
            for(var row:rows) {
                long id=SalesQuoteQueries.number(row,"id"),company=SalesQuoteQueries.number(row,"company_id");cursor=id;
                batch.run(company,"sales-quote-expiry:"+id,id,()->service.expire(tenant,company,id));
            }
        }
    }
}
