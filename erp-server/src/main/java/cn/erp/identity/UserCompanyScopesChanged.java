package cn.erp.identity;

/** 数据范围保存后同步清理依赖的组织资料，监听器失败时整个范围调整回滚。 */
public record UserCompanyScopesChanged(CompanyAccess actor,long userId,java.util.Set<Long> removedCompanies) {}
