package cn.erp.identity;

import java.util.Map;
import java.util.regex.Pattern;

/** 设备令牌覆盖仓储与审批操作，仍由对应业务入口执行权限校验。 */
final class DeviceRoutes {
    private static final String ID = "/[1-9][0-9]{0,18}";
    private static final Map<String,Pattern> ROUTES = Map.of(
            "GET",paths("/ws/v1/events","/api/v1/auth/me",
                    "/api/v1/purchase-orders(?:"+ID+")?", "/api/v1/goods-receipts(?:"+ID+")?",
                    "/api/v1/purchase-order-lines",
                    "/api/v1/inventory-balances", "/api/v1/inventory-tracking/options",
                    "/api/v1/warehouses"+ID+"/locations(?:/balances)?",
                    "/api/v1/wms/(?:tasks|picks|waves|packs|moves|count-tasks)(?:"+ID+")?",
                    "/api/v1/wms/(?:warehouses|scan/resolve|task-admin/statistics)",
                    "/api/v1/workflow/(?:tasks/todo|todo-count|instances"+ID+")"),
            "POST",paths("/api/v1/goods-receipts(?:"
                            +ID+"/(?:submit|post))?",
                    "/api/v1/wms/(?:tasks|picks)"+ID+"/(?:claim|finish|lines"+ID+"/confirm)",
                    "/api/v1/wms/count-tasks"+ID+"/(?:claim|confirm)",
                    "/api/v1/wms/waves"+ID+"/claim", "/api/v1/wms/packs",
                    "/api/v1/wms/moves(?:"+ID+"/post)?","/api/v1/wms/replenishments"+ID+"/post",
                    "/api/v1/labels/print-jobs", "/api/v1/workflow/tasks"+ID+"/(?:approve|reject)"),
            "PUT",paths("/api/v1/goods-receipts"+ID, "/api/v1/wms/moves"+ID),
            "DELETE",paths("/api/v1/auth/devices/current"));

    private DeviceRoutes() {}

    static boolean allows(String method,String path) {
        var allowed=ROUTES.get(method);
        return allowed!=null && allowed.matcher(path).matches();
    }

    private static Pattern paths(String... paths) { return Pattern.compile(String.join("|",paths)); }
}
