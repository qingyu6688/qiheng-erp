package cn.erp.identity;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

final class AdminModels {
    private AdminModels() {}

    record Page<T>(List<T> items, String nextCursor) {}

    record CompanyInput(
            @NotBlank(message="请输入公司编码") @Pattern(regexp="[A-Za-z0-9_-]{1,16}", message="公司编码限 16 位字母、数字、下划线和短横线") String code,
            @NotBlank(message="请输入公司名称") @Size(max=100,message="公司名称最多 100 字") String name,
            @Size(max=32,message="税号最多 32 字") String taxNo,
            @Size(max=200,message="地址最多 200 字") String address,
            @Size(max=32,message="电话最多 32 字") String phone,
            @PositiveOrZero(message="版本号不能为负") Long version) {}

    record CompanyView(String id,String code,String name,String taxNo,String address,String phone,
                       String status,long version,List<String> allowedActions) {}

    record PolicyInput(@Pattern(regexp="general|small",message="请选择纳税人身份") @NotNull(message="请选择纳税人身份") String taxpayerType,
                       @Min(value=1,message="有效期至少1天") @Max(value=365,message="有效期最多365天") int reservationValidDays,
                       @Min(value=0,message="宽限期不能为负") @Max(value=90,message="宽限期最多90天") int reservationGraceDays,
                       @NotNull(message="请输入超收容差") @DecimalMax(value="10.00",message="超收容差最多10%") @Pattern(regexp="(?:[0-9]|10)(?:\\.[0-9]{1,2})?",message="容差为0到10的两位小数") String overReceiptPct,
                       @NotNull(message="请选择审批经办分离规则") Boolean separateOrderApprover,@NotNull(message="请选择收付经办分离规则") Boolean separateReceiptConfirmer,
                       @NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version,
                       @Pattern(regexp="warn|approval|block",message="最低售价控制方式无效") String belowMinPricePolicy, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) cn.erp.shared.PaymentTerms.Term defaultPaymentTerms, Boolean purchaseFeeCostEnabled, @Pattern(regexp="amount|quantity|weight",message="采购费用分摊方式无效") String purchaseFeeAllocationMethod,
                       @Pattern(regexp="[0-9]{1,22}(?:\\.[0-9]{1,2})?",message="进项核对容差须为非负金额，最多两位小数") String invoiceMatchTolerance) {
        public PolicyInput(String taxpayerType,int reservationValidDays,int reservationGraceDays,String overReceiptPct,Boolean separateOrderApprover,Boolean separateReceiptConfirmer,Long version,String belowMinPricePolicy,cn.erp.shared.PaymentTerms.Term defaultPaymentTerms,Boolean purchaseFeeCostEnabled,String purchaseFeeAllocationMethod) {this(taxpayerType,reservationValidDays,reservationGraceDays,overReceiptPct,separateOrderApprover,separateReceiptConfirmer,version,belowMinPricePolicy,defaultPaymentTerms,purchaseFeeCostEnabled,purchaseFeeAllocationMethod,null);}
        public PolicyInput(String taxpayerType,int reservationValidDays,int reservationGraceDays,String overReceiptPct,Boolean separateOrderApprover,Boolean separateReceiptConfirmer,Long version,String belowMinPricePolicy,cn.erp.shared.PaymentTerms.Term defaultPaymentTerms) {this(taxpayerType,reservationValidDays,reservationGraceDays,overReceiptPct,separateOrderApprover,separateReceiptConfirmer,version,belowMinPricePolicy,defaultPaymentTerms,null,null);}
        public PolicyInput(String taxpayerType,int reservationValidDays,int reservationGraceDays,String overReceiptPct,Boolean separateOrderApprover,Boolean separateReceiptConfirmer,Long version,String belowMinPricePolicy) {this(taxpayerType,reservationValidDays,reservationGraceDays,overReceiptPct,separateOrderApprover,separateReceiptConfirmer,version,belowMinPricePolicy,null);}
        public PolicyInput(String taxpayerType,int reservationValidDays,int reservationGraceDays,String overReceiptPct,Boolean separateOrderApprover,Boolean separateReceiptConfirmer,Long version) {this(taxpayerType,reservationValidDays,reservationGraceDays,overReceiptPct,separateOrderApprover,separateReceiptConfirmer,version,null);}
    }
    record PolicyView(String taxpayerType,int reservationValidDays,int reservationGraceDays,String overReceiptPct,Boolean separateOrderApprover,Boolean separateReceiptConfirmer,long version,String belowMinPricePolicy, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) cn.erp.shared.PaymentTerms.Term defaultPaymentTerms, Boolean purchaseFeeCostEnabled, String purchaseFeeAllocationMethod,String invoiceMatchTolerance) {
        public PolicyView(String taxpayerType,int reservationValidDays,int reservationGraceDays,String overReceiptPct,Boolean separateOrderApprover,Boolean separateReceiptConfirmer,long version,String belowMinPricePolicy,cn.erp.shared.PaymentTerms.Term defaultPaymentTerms,Boolean purchaseFeeCostEnabled,String purchaseFeeAllocationMethod) {this(taxpayerType,reservationValidDays,reservationGraceDays,overReceiptPct,separateOrderApprover,separateReceiptConfirmer,version,belowMinPricePolicy,defaultPaymentTerms,purchaseFeeCostEnabled,purchaseFeeAllocationMethod,"1.00");}
        public PolicyView(String taxpayerType,int reservationValidDays,int reservationGraceDays,String overReceiptPct,Boolean separateOrderApprover,Boolean separateReceiptConfirmer,long version,String belowMinPricePolicy,cn.erp.shared.PaymentTerms.Term defaultPaymentTerms) {this(taxpayerType,reservationValidDays,reservationGraceDays,overReceiptPct,separateOrderApprover,separateReceiptConfirmer,version,belowMinPricePolicy,defaultPaymentTerms,true,"amount");}
        public PolicyView(String taxpayerType,int reservationValidDays,int reservationGraceDays,String overReceiptPct,Boolean separateOrderApprover,Boolean separateReceiptConfirmer,long version,String belowMinPricePolicy) {this(taxpayerType,reservationValidDays,reservationGraceDays,overReceiptPct,separateOrderApprover,separateReceiptConfirmer,version,belowMinPricePolicy,null);}
        public PolicyView(String taxpayerType,int reservationValidDays,int reservationGraceDays,String overReceiptPct,Boolean separateOrderApprover,Boolean separateReceiptConfirmer,long version) {this(taxpayerType,reservationValidDays,reservationGraceDays,overReceiptPct,separateOrderApprover,separateReceiptConfirmer,version,"warn");}
    }

    record UserInput(@NotBlank(message="请输入用户名") @Pattern(regexp="[A-Za-z0-9_.-]{1,64}",message="用户名限 64 位字母、数字、点、短横线和下划线") String username,
                     @NotBlank(message="请输入姓名") @Size(max=64,message="姓名最多 64 字") String displayName,
                     @NotBlank(message="请输入初始密码") @Size(max=72,message="密码过长") String password,
                     @Size(max=32,message="手机号最多 32 字") String mobile) {}

    record UserEdit(@NotBlank(message="请输入姓名") @Size(max=64,message="姓名最多 64 字") String displayName,
                    @Size(max=32,message="手机号最多 32 字") String mobile,
                    @NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version) {}

    record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version) {}
    record ResetPassword(@NotBlank(message="请输入新密码") @Size(max=72,message="密码过长") String password,
                         @NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version) {}
    record RoleInput(@NotBlank(message="请输入角色编码") @Size(max=64,message="角色编码最多 64 字") String code,
                     @NotBlank(message="请输入角色名称") @Size(max=64,message="角色名称最多 64 字") String name,
                     @Pattern(regexp="active|disabled",message="角色状态无效") String status,
                     @PositiveOrZero(message="版本号不能为负") Long version) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record UserView(String id,String username,String displayName,String mobile,String status,long version,
                    boolean mustChangePassword,List<String> roleIds,List<DataScope> dataScopes) {}
    record RoleView(String id,String code,String name,String status,long version,List<String> permissionCodes) {}
    record RoleAssignment(@NotNull(message="请选择角色") @Size(max=100,message="角色数量超限") List<@NotBlank(message="角色编号不能为空") @Pattern(regexp="[1-9][0-9]{0,18}",message="角色编号无效") String> roleIds,
                          @NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version) {}
    record PermissionAssignment(@NotNull(message="请选择权限") @Size(max=500,message="权限数量超限") List<@NotBlank(message="权限码不能为空") @Size(max=64,message="权限码过长") String> permissionCodes,
                                @NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version) {}
    record DataScope(@NotBlank(message="请选择公司") @Pattern(regexp="[1-9][0-9]{0,18}",message="公司编号无效") String companyId,
                     @NotNull(message="请选择范围") @Pattern(regexp="company|warehouse",message="范围无效") String scopeType,
                     @Pattern(regexp="[1-9][0-9]{0,18}",message="仓库编号无效") String warehouseId) {}
    record ScopeAssignment(@NotNull(message="请提供数据范围") @Size(max=200,message="数据范围数量超限") List<@NotNull(message="数据范围不能为空") @jakarta.validation.Valid DataScope> scopes,
                           @NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version) {}
}
