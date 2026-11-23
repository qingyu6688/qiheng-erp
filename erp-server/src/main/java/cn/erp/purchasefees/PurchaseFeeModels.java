package cn.erp.purchasefees;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class PurchaseFeeModels {
    private PurchaseFeeModels() {}
    public record FeeInput(@NotBlank(message="请选择费用类型") String feeType,
                           @NotBlank(message="请输入含税费用") String amount,
                           @NotBlank(message="请输入税率") String taxRate,
                           @Size(max=200,message="费用备注最多200字") String remark) {}
    public record Input(Long version, @Positive(message="请选择承运商或费用供应商") long partnerId,
                        @NotNull(message="请选择费用日期") LocalDate businessDate,
                        @NotBlank(message="请选择分摊方式") String allocationMethod,
                        @NotBlank(message="请填写费用原因") @Size(max=500,message="费用原因最多500字") String reason,
                        @NotEmpty(message="请添加费用明细") @Size(max=50,message="费用明细最多50行") List<@Valid FeeInput> fees,
                        @NotEmpty(message="请选择收货来源") @Size(max=100,message="最多选择100个收货行") List<@Positive Long> receiptLineIds) {}
    public record Command(@NotNull(message="请提供版本号") Long version, @Size(max=500,message="操作意见最多500字") String reason,
                          @Size(max=100,message="自选节点最多100个") Map<String,List<Long>> workflowAssignees) {
        public Command(Long version,String reason) {this(version,reason,null);}
    }
}
