package cn.erp.shared;

/** 供业务命令复检当前仓库授权，不依赖身份模块的实现类型。 */
public interface WarehouseScope extends ActorScope {
    boolean allWarehouses();
    boolean allowsWarehouse(long id);
}
