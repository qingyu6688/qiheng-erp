package cn.erp.purchasing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
public final class RequisitionModels {
    private RequisitionModels() {}
    public record Input(@NotNull(message="请选择业务日期") LocalDate businessDate,
        @Size(max=500,message="备注最多500字") String remark,
        @NotEmpty(message="请填写请购明细") @Size(max=500,message="请购最多500行") List<@NotNull(message="请购行不能为空") @Valid Line> lines,Long version) {}
    public record Line(String id,@NotBlank(message="请选择商品") String skuId,@NotBlank(message="请选择单位") String unitId,
        @NotBlank(message="请输入数量") String qty,LocalDate needDate) {}
}
