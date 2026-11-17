package cn.erp.mfg;

import cn.erp.inventory.InventoryModels.StockSelection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

final class MfgStockModels {
    private MfgStockModels() {}

    enum Type {
        ISSUE("mfg_issue", "MI"), RECEIPT("mfg_receipt", "MF");

        final String resource;
        final String head;
        final String line;
        final String numberPrefix;

        Type(String resource, String numberPrefix) {
            this.resource = resource;
            this.head = resource + "_doc";
            this.line = resource + "_line";
            this.numberPrefix = numberPrefix;
        }

        String permission(String action) {
            return resource + ":" + switch (action) {
                case "reject" -> "approve";
                case "cancel" -> "update";
                default -> action;
            };
        }
    }

    record Draft(Long workOrderId, Long warehouseId, LocalDate businessDate, String direction,
                 String remark, Long version, List<InputLine> lines) {}

    record InputLine(Long materialId, Long skuId, String qty, StockSelection stockSelection,
                     Long originalIssueLineId) {}

    record Revision(Long version, String reason, Map<String, List<Long>> workflowAssignees) {}

    record Filter(Long workOrderId, Long warehouseId, String direction, String status, Long cursor, int limit) {}
}
