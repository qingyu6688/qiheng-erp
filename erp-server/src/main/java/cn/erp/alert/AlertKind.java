package cn.erp.alert;

import java.math.BigDecimal;
import java.util.List;

enum AlertKind {
    CREDIT_EXCEEDED("credit_exceeded","信用额度超额","critical","realtime","0","CNY"),
    RECEIVABLE_OVERDUE("receivable_overdue","应收逾期","warning","daily","0","天"),
    NEGATIVE_MARGIN("negative_margin","销售负毛利","warning","realtime","0","CNY"),
    STOCK_BELOW_SAFETY("stock_below_safety","低于安全库存","warning","realtime",null,null),
    STOCK_SLOW_MOVING("stock_slow_moving","库存呆滞","info","daily","90","天"),
    BATCH_NEAR_EXPIRY("batch_near_expiry","批次临期","warning","daily",null,"天"),
    BATCH_EXPIRED("batch_expired","批次过期","critical","hourly",null,null),
    QC_FAILED("qc_failed","来料检验不合格","warning","realtime",null,null),
    PRICE_DEVIATION("price_deviation","采购价格偏离","warning","realtime","20","%"),
    FREQUENT_REVERSAL("frequent_reversal","频繁冲销","warning","hourly","3","次"),
    DUPLICATE_PAYMENT("duplicate_payment","疑似重复付款","critical","realtime",null,null),
    WORKFLOW_OVERDUE("workflow_overdue","审批超时","warning","hourly","0","小时"),
    JOB_FAILED("job_failed","任务执行失败","warning","hourly",null,null),
    COLLECTION_DUE("collection_due","催收计划到期","warning","daily",null,null);

    final String code;
    final String title;
    final String severity;
    final String schedule;
    final BigDecimal threshold;
    final String unit;

    AlertKind(String code,String title,String severity,String schedule,String threshold,String unit) {
        this.code=code;this.title=title;this.severity=severity;this.schedule=schedule;
        this.threshold=threshold==null?null:new BigDecimal(threshold);this.unit=unit;
    }

    static AlertKind parse(String value) {
        for (var kind : values()) if (kind.code.equals(value)) return kind;
        throw AlertRules.invalid("预警规则不存在");
    }

    List<AlertRules.Receiver> defaultReceivers() {
        return switch (this) {
            case BATCH_NEAR_EXPIRY,BATCH_EXPIRED,STOCK_BELOW_SAFETY,STOCK_SLOW_MOVING -> List.of(
                    new AlertRules.Receiver("role-code","WAREHOUSE_ADMIN"),new AlertRules.Receiver("role-code","SYSTEM_ADMIN"));
            case COLLECTION_DUE,WORKFLOW_OVERDUE -> List.of(new AlertRules.Receiver("owner",null));
            default -> List.of(new AlertRules.Receiver("owner",null),new AlertRules.Receiver("role-code","SYSTEM_ADMIN"));
        };
    }
}
