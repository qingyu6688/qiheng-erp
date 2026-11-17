package cn.erp.openapi;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.erp.shared.ApiException;

final class OpenPolicy {
    record Capability(String code, String label) {}

    static final String VIEW = "open_platform:view";
    static final String MANAGE = "open_platform:manage";
    static final List<Capability> SCOPES = List.of(
            new Capability("sku:view", "查看 SKU"), new Capability("sku:create", "新建 SKU"),
            new Capability("partner:view", "查看往来单位"), new Capability("partner:create", "新建往来单位"),
            new Capability("warehouse:view", "查看仓库"), new Capability("inventory:view", "查看可用库存"),
            new Capability("sales_order:view", "查看销售订单"), new Capability("sales_order:create", "创建销售订单"),
            new Capability("sales_order:submit", "提交销售订单"), new Capability("sales_order:cancel", "取消销售订单"),
            new Capability("purchase_order:view", "查看采购订单"), new Capability("purchase_order:create", "创建采购订单"),
            new Capability("purchase_order:submit", "提交采购订单"), new Capability("shipment:view", "查看发货单"),
            new Capability("goods_receipt:view", "查看收货单"), new Capability("receipt:view", "查看收款单"),
            new Capability("receipt:create", "登记收款草稿"),
            new Capability("payment:create", "登记付款草稿"),
            new Capability("workflow_instance:view_all", "订阅工作流触发事件"));
    static final List<Capability> EVENTS = List.of(
            new Capability("sales.order.approved", "销售订单审核通过"),
            new Capability("sales.shipment.posted", "销售发货过账"),
            new Capability("sales.shipment.signed", "销售发货签收"),
            new Capability("purchase.receipt.posted", "采购收货过账"),
            new Capability("finance.receipt.confirmed", "收款单确认"),
            new Capability("inventory.balance.changed", "库存余额变动"),
            new Capability("workflow.trigger", "工作流触发事件"));
    static final Set<String> SCOPE_CODES = SCOPES.stream().map(Capability::code)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    static final Map<String, String> EVENT_SCOPES = Map.of(
            "sales.order.approved", "sales_order:view", "sales.shipment.posted", "shipment:view",
            "sales.shipment.signed", "shipment:view", "purchase.receipt.posted", "goods_receipt:view",
            "finance.receipt.confirmed", "receipt:view", "inventory.balance.changed", "inventory:view",
            "workflow.trigger", "workflow_instance:view_all");

    private OpenPolicy() {}

    static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_FAILED",message); }
    static ApiException denied() { return new ApiException(403,"OPEN_FORBIDDEN","应用无权执行此操作"); }
    static ApiException missing() { return new ApiException(404,"NOT_FOUND","记录不存在或无权查看"); }

    static long id(String value) {
        try {
            if (value != null && value.matches("[1-9][0-9]{0,18}")) return Long.parseLong(value);
        } catch (NumberFormatException invalid) { throw invalid("编号超出有效范围"); }
        throw invalid("编号格式无效");
    }

    static Cidr cidr(String raw) {
        if (raw == null || raw.length() > 64) throw invalid("IP 白名单地址无效");
        String[] parts = raw.split("/", -1);
        try {
            byte[] base = address(parts[0]);
            int prefix = parts.length == 1 ? base.length * 8 : Integer.parseInt(parts[1]);
            if (parts.length <= 2 && prefix >= 0 && prefix <= base.length * 8) return new Cidr(base, prefix);
        } catch (UnknownHostException | NumberFormatException invalid) {
            throw invalid("IP 白名单须填写 IPv4、IPv6 或 CIDR 地址");
        }
        throw invalid("IP 白名单网段无效");
    }

    static byte[] address(String value) throws UnknownHostException {
        if (value.indexOf(':') >= 0 && value.matches("[0-9a-fA-F:.]+")) return InetAddress.getByName(value).getAddress();
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new UnknownHostException();
        byte[] result = new byte[4];
        for (int i=0; i<parts.length; i++) {
            if (!parts[i].matches("(?:0|[1-9][0-9]{0,2})")) throw new UnknownHostException();
            int octet = Integer.parseInt(parts[i]);
            if (octet > 255) throw new UnknownHostException();
            result[i] = (byte) octet;
        }
        return result;
    }

    record Cidr(byte[] base, int prefix) {
        boolean contains(byte[] address) {
            if (address.length != base.length) return false;
            int bytes = prefix / 8;
            for (int i=0; i<bytes; i++) if (address[i] != base[i]) return false;
            int bits = prefix % 8;
            return bits == 0 || ((address[bytes] ^ base[bytes]) & (0xff << (Byte.SIZE-bits))) == 0;
        }
    }
}
