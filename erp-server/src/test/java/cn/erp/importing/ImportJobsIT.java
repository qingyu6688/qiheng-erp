package cn.erp.importing;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.opening.OpeningService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.shared.ApiException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class ImportJobsIT extends ImportTestData {
    @Autowired OpeningService opening;
    @Autowired ReconciliationChecks checks;
    @Test void skuPartialRetainsOnlyValidRowsAndUsesExistingProductAndBaseUnit() {
        long job=id(create("sku","PRODUCT,NEW,新SKU,规格,0.13,12.25,18,active\r\nMISSING,BAD,错误SKU,,0.13,,18,active\r\nPRODUCT,EXCESS,错误精度,,0.1234567,,18,active"));
        assertThat(processor.processNext()).isTrue();
        assertThat(jobs.get(maker,job)).containsEntry("status","partial").containsEntry("successRows",1).containsEntry("failedRows",2);
        assertThat(count("sku")).isEqualTo(2);assertThat(count("product")).isEqualTo(1);
        long imported=jdbc.queryForObject("SELECT id FROM sku WHERE tenant_id=? AND code='NEW'",Long.class,tenant);
        assertThat(jdbc.queryForObject("SELECT unit_id FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND is_base=1",Long.class,tenant,imported)).isEqualTo(unit);
        assertThat(jobs.errors(maker,job,null,100).get("items")).asList().hasSize(2);
    }
    @Test void partnerRolesAndQuotedNamesUseExistingPartnerValidation() {
        long job=id(create("partner","001,\"甲,乙公司\",简称,,customer|supplier\r\n002,角色错误,,,invalid"));processor.processNext();
        assertThat(jobs.get(maker,job)).containsEntry("status","partial");
        assertThat(count("business_partner")).isEqualTo(1);assertThat(count("partner_role")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=?",String.class,tenant)).isEqualTo("甲,乙公司");
    }
    @Test void openingImportCreatesDraftOnlyThenRealTwoPersonApprovalPostingReconciles() {
        long job=id(create("opening_stock","SKU,件,3,30,库存期初"));processor.processNext();
        assertThat(jobs.get(maker,job)).containsEntry("status","succeeded");
        assertThat(count("inventory_movement")).isZero();assertThat(count("journal")).isZero();
        long target=jdbc.queryForObject("SELECT target_id FROM import_job_target WHERE job_id=?",Long.class,job);
        var draft=opening.get(maker,true,target);assertThat(draft).containsEntry("status","draft");
        var pending=opening.command(maker,true,target,"submit",key(),new OpeningService.Version(version(draft),null));
        assertThatThrownBy(()->opening.command(maker,true,target,"approve",key(),new OpeningService.Version(version(pending),null))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo("SAME_OPERATOR_NOT_ALLOWED"));
        var approved=opening.command(reviewer,true,target,"approve",key(),new OpeningService.Version(version(pending),null));
        opening.command(maker,true,target,"post",key(),new OpeningService.Version(version(approved),null));
        var whole=new CompanyAccess(tenant,user,company,true,Set.of(),maker.permissions());
        for(String type:ReconciliationChecks.TYPES)assertThat(checks.check(whole,type,type.equals("period_close")?period:null)).as(type).isEmpty();
        assertThat(count("inventory_movement")).isEqualTo(1);assertThat(count("journal")).isEqualTo(1);
    }
    @Test void openingBadRowsAreAtomicAndHaveChineseColumnErrors() {
        long job=id(create("opening_stock","SKU,件,0,10,数量错误\r\nSKU,件,1,1.001,金额精度错误\r\nMISSING,件,1,1,资料错误"));processor.processNext();
        assertThat(jobs.get(maker,job)).containsEntry("status","failed").containsEntry("failedRows",3);
        assertThat(count("stock_adjustment")).isZero();assertThat(count("stock_adjustment_line")).isZero();
        assertThat(jobs.errors(maker,job,null,100).get("items").toString()).contains("qty","totalCost","skuCode","数量须大于零");
    }
    @Test void identicalFingerprintAndIdempotencyAcrossConcurrentKeysCreateOneJobAndOneDraft() throws Exception {
        byte[] bytes=csv("opening_stock","SKU,件,1,10,并发期初");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<Long>>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{start.await();return id(jobs.create(maker,key(),"opening_stock",warehouse,DAY,"x.csv",bytes));}));
            start.countDown();long first=futures.getFirst().get(15,TimeUnit.SECONDS);
            assertThat(futures.getLast().get(15,TimeUnit.SECONDS)).isEqualTo(first);
            processor.processNext();assertThat(count("import_job")).isEqualTo(1);assertThat(count("attachment")).isEqualTo(1);assertThat(count("stock_adjustment")).isEqualTo(1);
        }
    }
    @Test void expiredLeaseResumesFromCommittedCheckpointWithoutDuplicateTargets() {
        long job=id(create("opening_stock","SKU,件,1,10,第一行\r\nSKU,件,2,20,第二行"));
        processor.processNext(1);
        assertThat(count("stock_adjustment")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT checkpoint_row FROM import_job WHERE id=?",Integer.class,job)).isEqualTo(1);
        jdbc.update("UPDATE import_job SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE) WHERE id=?",job);
        processor.processNext();processor.processNext();
        assertThat(jobs.get(maker,job)).containsEntry("status","succeeded").containsEntry("successRows",2);
        assertThat(count("stock_adjustment")).isEqualTo(2);assertThat(count("import_job_target")).isEqualTo(2);
    }
    @Test void costCreationPermissionAndOwnershipAreCheckedOnEveryReadAndExecution() {
        var noCost=new CompanyAccess(tenant,user,company,false,Set.of(warehouse),maker.permissions().stream().filter(p->!p.equals("cost:view")).collect(java.util.stream.Collectors.toSet()));
        assertThatThrownBy(()->jobs.create(noCost,key(),"sku",null,null,"a.csv",csv("sku","PRODUCT,N,新,,,10,1,active"))).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(403));
        long job=id(jobs.create(noCost,key(),"sku",null,null,"a.csv",csv("sku","PRODUCT,N,新,,,,1,active")));
        assertThatThrownBy(()->jobs.get(reviewer,job)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(404));
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='sku:create')",tenant,role);
        processor.processNext();assertThat(count("sku")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM import_job WHERE id=?",String.class,job)).isEqualTo("failed");
    }
    @Test void revokedWarehouseOrDisabledActorStopsWorkerAndHidesExistingResults() {
        long job=id(create("opening_stock","SKU,件,1,10,撤权"));
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND warehouse_id=?",tenant,user,warehouse);
        processor.processNext();assertThat(count("stock_adjustment")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM import_job WHERE id=?",String.class,job)).isEqualTo("failed");
        assertThatThrownBy(()->jobs.get(maker,job)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->jobs.get(reviewer,job)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(404));
        jdbc.update("UPDATE sys_user SET status='disabled' WHERE id=?",user);
        assertThat(jobs.list(maker,null,null,20).get("items")).asList().isEmpty();
    }
    @Test void revokedImportPermissionPreventsBackgroundWritesAndCurrentRead() {
        long job=id(create("partner","P,单位,,,supplier"));
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='import:partner')",tenant,role);
        processor.processNext();assertThat(count("business_partner")).isZero();
        assertThatThrownBy(()->jobs.get(maker,job)).isInstanceOf(ApiException.class);
    }
    @Test void sourceStorage503RetriesWithBackoffAndKeepsCommittedCheckpoint() {
        long job=id(create("opening_stock","SKU,件,1,10,第一行\r\nSKU,件,2,20,第二行"));processor.processNext(1);
        String storage=source(job);byte[] bytes=files.read(storage);files.delete(storage);expireLease(job);
        processor.processNext();
        assertThat(jobs.get(maker,job)).containsEntry("status","pending").containsEntry("checkpointRow",1).containsEntry("successRows",1).containsEntry("failedRows",0).containsEntry("attempts",2);
        assertThat(processor.processNext()).isFalse();
        files.put(storage,bytes,"text/csv");ready(job);processor.processNext();
        assertThat(jobs.get(maker,job)).containsEntry("status","succeeded").containsEntry("successRows",2);
        assertThat(count("stock_adjustment")).isEqualTo(2);assertThat(count("import_job_error")).isZero();
    }
    @Test void exhaustedStorageRetriesCanBeManuallyResumedWithOneIdempotentRetry() {
        long job=id(create("partner","P,恢复单位,,,supplier"));String storage=source(job);byte[] bytes=files.read(storage);files.delete(storage);
        for(int attempt=0;attempt<3;attempt++){ready(job);processor.processNext();}
        assertThat(jobs.get(maker,job)).containsEntry("status","failed").containsEntry("attempts",3).containsEntry("canRetry",true);
        files.put(storage,bytes,"text/csv");String retryKey=key();
        assertThat(jobs.retry(maker,job,retryKey)).containsEntry("status","pending").containsEntry("attempts",0);
        assertThat(jobs.retry(maker,job,retryKey)).containsEntry("status","pending");processor.processNext();
        assertThat(count("business_partner")).isEqualTo(1);assertThat(count("import_job_target")).isEqualTo(1);
        jobs.retry(maker,job,retryKey);assertThat(count("business_partner")).isEqualTo(1);
        assertThatThrownBy(()->jobs.retry(maker,job,key())).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo("IMPORT_RETRY_NOT_ALLOWED"));
    }
    @Test void permissionRestorationResumesOnlyUnprocessedRowsAndNeverRepeatsRejectedRows() {
        long job=id(create("opening_stock","SKU,件,0,10,错误行\r\nSKU,件,1,10,第一有效行\r\nSKU,件,2,20,第二有效行"));processor.processNext(2);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='import:opening_stock')",tenant,role);
        expireLease(job);processor.processNext();assertThat(count("stock_adjustment")).isEqualTo(1);
        assertThatThrownBy(()->jobs.retry(maker,job,key())).isInstanceOf(ApiException.class);
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code='import:opening_stock'",cn.erp.shared.Ids.next(),tenant,role);
        jobs.retry(maker,job,key());processor.processNext();
        assertThat(jobs.get(maker,job)).containsEntry("status","partial").containsEntry("successRows",2).containsEntry("failedRows",1).containsEntry("canRetry",false);
        assertThat(count("stock_adjustment")).isEqualTo(2);assertThat(count("import_job_error")).isEqualTo(1);
    }
    @Test void permissionFilteredPaginationDoesNotReturnTheLookaheadRowTwice() {
        long old=id(create("partner","P1,旧任务,,,supplier")),middle=id(create("partner","P2,中间任务,,,supplier"));
        create("sku","PRODUCT,N,无权任务,,,,1,active");
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='import:sku')",tenant,role);
        var first=jobs.list(maker,null,null,2);assertThat(first.get("items").toString()).contains(Long.toString(middle)).doesNotContain(Long.toString(old));
        var second=jobs.list(maker,null,Long.parseLong(first.get("nextCursor").toString()),2);
        assertThat(second.get("items").toString()).contains(Long.toString(old)).doesNotContain(Long.toString(middle));
    }
    private String source(long job){return jdbc.queryForObject("SELECT a.storage_key FROM import_job j JOIN attachment a ON a.id=j.attachment_id WHERE j.id=?",String.class,job);}
    private void ready(long job){jdbc.update("UPDATE import_job SET next_attempt_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",job);}
    private void expireLease(long job){jdbc.update("UPDATE import_job SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",job);}
}
