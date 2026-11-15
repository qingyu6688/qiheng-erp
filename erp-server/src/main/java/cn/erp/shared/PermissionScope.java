package cn.erp.shared;

/** 历史命令回放依照当前权限复核，不依赖身份实现。 */
public interface PermissionScope extends ActorScope {
    boolean hasPermission(String permission);
}
