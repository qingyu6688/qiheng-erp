package cn.erp.shared;

import java.time.LocalDate;
import java.util.Map;

/** 冲销统一服务复用生产单据范围和成本屏障。 */
public interface ManufacturingReversalPort {
    static boolean supports(String type) { return "mfg_issue_doc".equals(type) || "mfg_receipt_doc".equals(type); }

    static String viewPermission(String type) {
        return (supports(type) ? type.substring(0, type.length() - 4) : type) + ":view";
    }

    Map<String, Object> source(ActorScope actor, String type, long id, boolean lock);

    void prepare(ActorScope actor, String type, long id, long period, LocalDate businessDate);

    void dependencies(ActorScope actor, String type, long id);

    void restoreProgress(ActorScope actor, String type, Map<String, Object> source);
}
