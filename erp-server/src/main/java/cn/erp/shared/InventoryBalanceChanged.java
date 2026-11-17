package cn.erp.shared;

import java.util.List;

/** 与库存变化共用业务事务，由开放平台按公司和 SKU 合并。 */
public record InventoryBalanceChanged(long tenantId,long companyId,List<Long> skuIds) {}
