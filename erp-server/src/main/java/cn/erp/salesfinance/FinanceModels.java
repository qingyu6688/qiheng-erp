package cn.erp.salesfinance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class FinanceModels {
    private FinanceModels() {}
    public record Receipt(
        @NotNull(message="请选择收款类型") @Pattern(regexp="customer|supplier_refund|other_income",message="收款类型无效") String receiptType,
        Long partnerId, Long incomeItemId,
        @NotNull(message="请选择结算账户") Long cashAccountId,
        @NotBlank(message="请输入收款金额") String amount,
        @NotNull(message="请选择业务日期") LocalDate businessDate,
        Long relatedOrderId, @Size(max=64,message="银行流水号最多64字") String bankSerialNo,
        @Size(max=200,message="备注最多200字") String remark, Long version, @Positive(message="业务员编号无效") Long salesUserId) {
        public Receipt(String receiptType,Long partnerId,Long incomeItemId,Long cashAccountId,String amount,LocalDate businessDate,Long relatedOrderId,String bankSerialNo,String remark,Long version) {this(receiptType,partnerId,incomeItemId,cashAccountId,amount,businessDate,relatedOrderId,bankSerialNo,remark,version,null);}
    }
    public record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version,
                          LocalDate businessDate,@Size(max=200,message="原因最多200字") String reason,@Size(max=100,message="自选节点最多100个") java.util.Map<String,List<Long>> workflowAssignees) {
        public Version(Long version,LocalDate businessDate,String reason) {this(version,businessDate,reason,null);}
    }
    public record ReturnInput(
        @NotNull(message="请选择客户") Long customerId,@NotNull(message="请选择退回仓库") Long warehouseId,
        @NotNull(message="请选择业务日期") LocalDate businessDate,@NotBlank(message="请填写退货原因") @Size(max=200,message="原因最多200字") String reason,
        @NotEmpty(message="请填写退货明细") @Size(max=500,message="最多500行") List<@NotNull @Valid ReturnLine> lines,Long version, @Positive(message="业务员编号无效") Long salesUserId) {
        public ReturnInput(Long customerId,Long warehouseId,LocalDate businessDate,String reason,List<ReturnLine> lines,Long version) {this(customerId,warehouseId,businessDate,reason,lines,version,null);}
    }
    public record ReturnLine(@NotNull(message="请选择原发货行") Long shipmentLineId,@NotNull(message="请选择退货单位") Long unitId,
        @NotBlank(message="请输入退货数量") String qty,
        @NotNull(message="请选择质检结果") @Pattern(regexp="pending|passed|failed",message="质检结果无效") String qualityResult) {}
    public record Refund(
        @NotNull(message="请选择客户") Long customerId,@NotNull(message="请选择原收款") Long originalReceiptId,
        @NotNull(message="请选择退款账户") Long cashAccountId,@NotBlank(message="请输入退款金额") String amount,
        @NotNull(message="请选择业务日期") LocalDate businessDate,
        @NotBlank(message="请填写退款原因") @Size(max=200,message="原因最多200字") String reason,
        @Size(max=200,message="额度来源最多200项") List<Long> creditItemIds,Long version) {}
    public record Paid(@NotNull(message="请提供版本号") Long version,@NotNull(message="请选择退款日期") LocalDate paidDate,
                       @NotBlank(message="请填写银行流水号") @Size(max=64,message="银行流水号最多64字") String bankSerialNo) {}
    public record Allocation(@NotNull(message="请选择借方未清项") Long debitItemId,
        @NotNull(message="请选择贷方未清项") Long creditItemId,@NotBlank(message="请填写核销金额") String amount) {}
    public record Allocations(@NotNull(message="请选择往来单位") Long partnerId,
        @NotEmpty(message="请选择核销记录") @Size(max=200,message="最多200对核销") List<@NotNull @Valid Allocation> allocations) {}
}
