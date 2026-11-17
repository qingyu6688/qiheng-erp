package cn.erp.crm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

final class CrmCycleModels {
    private CrmCycleModels() {}

    record Stage(@NotBlank String name, @NotBlank String winRate, @Min(0) Integer sort) {}
    record StageGroup(@NotBlank String name, @NotEmpty @Size(max = 100) List<@NotNull @Valid Stage> stages,
                      @Size(max = 200) List<@NotBlank String> departmentIds) {}

    record Product(@NotBlank String skuId, @NotBlank String unitId, @NotBlank String qty,
                   @NotBlank String unitPrice, String taxRate, String discountRate) {}
    record Business(@NotBlank String name, @NotBlank String partnerId, @NotBlank String stageGroupId,
                    @NotBlank String stageId, String expectedAmount, @NotNull LocalDate expectedDate,
                    @Size(max = 200) List<@NotNull @Valid Product> lines, Long version) {}
    record Action(@NotNull @Min(0) Long version, String stageId, @Size(max = 300) String reason,
                  @Size(max = 500) String comment, @Size(max = 100) Map<String, List<Long>> workflowAssignees) {}

    record Contract(@NotBlank String name, @NotBlank String partnerId, String businessId,
                    String signerContactId, @NotNull LocalDate signDate, @NotNull LocalDate startDate,
                    @NotNull LocalDate endDate, Boolean priceTaxInclusive,
                    @NotEmpty @Size(max = 200) List<@NotNull @Valid Product> lines, Long version) {}
    record OrderLine(@NotBlank String contractLineId, @NotBlank String warehouseId, @NotBlank String qty) {}
    record CreateOrder(@NotNull LocalDate businessDate, LocalDate deliveryDate,
                       @NotEmpty @Size(max = 200) List<@NotNull @Valid OrderLine> lines) {}

    record Plan(@NotBlank String contractId, @NotNull @Min(1) @Max(999) Integer periodNo,
                @NotNull LocalDate planDate, @NotBlank String planAmount, @Min(0) @Max(90) Integer remindDays) {}
    record Receivable(@NotBlank String planId, @NotBlank String receiptId, String amount) {}
    record Target(@NotBlank String targetType, @NotBlank String targetId, @NotNull @Min(1900) @Max(9999) Integer year,
                  @NotNull @Min(1) @Max(12) Integer month, @NotBlank String kind, @NotBlank String amount) {}
}
