package cn.erp.reference;

import jakarta.validation.constraints.*;

final class CashModels {
    private CashModels() {}
    record Account(
        @NotBlank(message="请输入账户编码") @Size(max=32,message="编码最多32字") String code,
        @NotBlank(message="请输入账户名称") @Size(max=100,message="名称最多100字") String name,
        @NotNull(message="请选择账户类型") @Pattern(regexp="cash|bank|other",message="账户类型无效") String accountType,
        @Size(max=100,message="银行名称最多100字") String bankName,
        @Size(max=64,message="账号最多64字") String accountNo, Long version) {}
    record Item(
        @NotBlank(message="请输入项目编码") @Size(max=32,message="编码最多32字") String code,
        @NotBlank(message="请输入项目名称") @Size(max=64,message="名称最多64字") String name,
        @NotNull(message="请选择收支类型") @Pattern(regexp="income|expense",message="收支类型无效") String itemType,
        @Pattern(regexp="active|disabled",message="状态无效") String status, Long version) {}
    record Period(@NotBlank(message="请选择期间") @Pattern(regexp="[0-9]{6}",message="期间格式应为年月，如202611") String period) {}
}
