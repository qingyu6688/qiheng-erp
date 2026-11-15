package cn.erp.reference;

import jakarta.validation.constraints.*;
import java.util.Set;

final class PartnerModels {
    private PartnerModels() {}

    record Partner(
        @NotBlank(message="请输入单位编码") @Size(max=32,message="编码最多32字") String code,
        @NotBlank(message="请输入单位名称") @Size(max=100,message="名称最多100字") String name,
        @Size(max=50,message="简称最多50字") String shortName,
        @Size(max=32,message="税号最多32字") String taxNo,
        @NotEmpty(message="请选择客户或供应商角色") Set<@Pattern(regexp="customer|supplier",message="往来角色无效") String> roles,
        Long version) {}
    record Contact(
        @NotBlank(message="请输入联系人姓名") @Size(max=50,message="姓名最多50字") String name,
        @Size(max=32,message="电话最多32字") String mobile,
        @Email(message="邮箱格式有误") @Size(max=100,message="邮箱最多100字") String email,
        @Size(max=200,message="地址最多200字") String address,
        boolean isDefault, Long version) {}
    record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负数") Long version) {}
}
