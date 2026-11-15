package cn.erp.purchasefinance;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public final class TreasuryModels {
    private TreasuryModels() {}
    public record Payment(
        @NotBlank(message="请选择付款类型") @Pattern(regexp="supplier|other_expense",message="付款类型无效") String paymentType,
        Long partnerId,Long expenseItemId,@NotNull(message="请选择付款账户") Long cashAccountId,
        @NotBlank(message="请输入付款金额") String amount,@NotNull(message="请选择业务日期") LocalDate businessDate,
        Long relatedOrderId,@Size(max=64,message="银行流水号最多64字") String bankSerialNo,
        @Size(max=200,message="备注最多200字") String remark,Long version) {}
    public record Transfer(
        @NotNull(message="请选择转出账户") Long fromAccountId,@NotNull(message="请选择转入账户") Long toAccountId,
        @NotBlank(message="请输入转账金额") String amount,String feeAmount,
        @NotNull(message="请选择业务日期") LocalDate businessDate,
        @Size(max=200,message="备注最多200字") String remark,Long version) {}
    public record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version,LocalDate businessDate) {}
}
