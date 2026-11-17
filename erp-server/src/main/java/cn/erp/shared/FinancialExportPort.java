package cn.erp.shared;

import java.util.Map;
import java.util.Set;

/** 财务复用平台导出队列，平台不依赖总账表结构或公式实现。 */
public interface FinancialExportPort {
    Set<String> TYPES =
            Set.of(
                    "gl-vouchers",
                    "gl-balance",
                    "gl-general",
                    "gl-detail",
                    "gl-multi_column",
                    "gl-quantity",
                    "gl-auxiliary",
                    "gl-balance_sheet",
                    "gl-income_statement",
                    "gl-cash_flow");

    static boolean supports(String type) {
        return type != null && TYPES.contains(type);
    }

    record File(String fileName, String contentType, byte[] bytes, int rowCount, String asOf) {}

    void authorize(ActorScope actor, String type);

    Map<String, Object> freeze(ActorScope actor, String type, Map<String, Object> query);

    File render(ActorScope actor, String type, Map<String, Object> query);
}
