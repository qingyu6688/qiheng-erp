package cn.erp.gl;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 金额和数量保持十进制字符串，辅助键固定为受支持的七个维度。 */
public final class GlModels {
    private GlModels() {}

    public record Version(
            @NotNull(message = "请提供版本号") @PositiveOrZero(message = "版本号无效") Long version,
            @Size(max = 500, message = "原因最多500字") String reason) {}

    public record Book(
            @NotBlank(message = "请选择会计准则") String standard,
            @NotNull(message = "请选择启用期间") @Positive(message = "期间编号无效") Long startPeriodId,
            String reportBasis,
            @NotNull(message = "请提供版本号") Long version) {}

    public record Confirmation(
            @NotNull(message = "请提供版本号") Long version,
            @NotBlank(message = "请填写财务核对说明") @Size(max = 500, message = "说明最多500字")
                    String statement) {}

    public record Subject(
            String code,
            String name,
            Long parentId,
            String category,
            String direction,
            boolean isCash,
            boolean isBank,
            List<String> auxTypes,
            Long customAuxTypeId,
            String quantityUnit,
            String profitRole,
            Long version) {}

    public record AuxType(String code, String name, String source, Long version) {}

    public record AuxItem(Long auxTypeId, String code, String name, Long version) {}

    public record CashFlow(
            String code,
            String name,
            String direction,
            String category,
            int sortNo,
            Long version) {}

    public record Word(String code, String printTitle, boolean isDefault, Long version) {}

    public record Digest(String content, Long version) {}

    public record Mapping(
            String ledgerAccountCode,
            Map<String, String> condition,
            Long subjectId,
            Map<String, String> auxRules,
            Long cashFlowItemId,
            int priority,
            boolean enabled,
            Long version) {}

    public record Aux(
            Long customerId,
            Long supplierId,
            Long deptId,
            Long employeeId,
            Long projectId,
            Long skuId,
            Long customId) {
        public static Aux empty() {
            return new Aux(0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }

        public List<Long> values() {
            return java.util.Arrays.asList(
                            customerId, supplierId, deptId, employeeId, projectId, skuId, customId)
                    .stream()
                    .map(n -> n == null ? 0L : n)
                    .toList();
        }
    }

    public record Line(
            String digest,
            Long subjectId,
            String direction,
            String amount,
            String quantity,
            String unitPrice,
            @Valid Aux aux,
            Long cashFlowItemId) {}

    public record Voucher(
            @NotNull(message = "请选择凭证字") Long wordId,
            @NotNull(message = "请选择凭证日期") LocalDate voucherDate,
            int attachmentCount,
            @NotEmpty(message = "请填写分录") @Size(max = 1000, message = "每张凭证最多1000行")
                    List<@NotNull(message = "分录不能为空") @Valid Line> lines,
            @Size(max = 500, message = "备注最多500字") String remark,
            Long version) {}

    public record VoucherTemplate(
            String code, String name, Long wordId, List<Line> lines, Long version) {}

    public record OpeningLine(
            Long subjectId,
            Aux aux,
            String openingDebit,
            String openingCredit,
            String yearDebit,
            String yearCredit,
            String annualOpeningDebit,
            String annualOpeningCredit,
            String quantity) {}

    public record Openings(
            @NotNull(message = "请提供账套版本") Long version,
            @NotNull(message = "请提供期初明细") @Size(max = 2000, message = "一次最多2000行")
                    List<@Valid OpeningLine> lines) {}

    public record Scheme(
            String code,
            String name,
            int sortNo,
            List<String> sourceCodes,
            Long targetSubjectId,
            boolean enabled,
            Long version) {}

    public record FormulaRow(String code, String name, String formula, boolean bold, int indent) {}

    public record FormulaCheck(String name, String left, String right) {}

    public record Report(
            String name, List<FormulaRow> rows, List<FormulaCheck> checks, Long version) {}

    public record ExportColumn(String field, String label) {}

    public record ExportFormat(
            String code, String name, String fileType, List<ExportColumn> columns, Long version) {}

    public record Batch(
            @NotEmpty(message = "请选择凭证") @Size(max = 100, message = "一次最多100张")
                    List<@NotNull(message = "凭证不能为空") @Valid BatchRow> vouchers,
            String reason) {}

    public record BatchRow(
            @NotNull(message = "凭证编号不能为空") Long id, @NotNull(message = "请提供凭证版本") Long version) {}

    public record VoucherFilter(
            Long periodId, Long wordId, String status, String source, Long cursor, Integer limit) {}

    public record Query(
            Long periodFrom,
            Long periodTo,
            Long subjectId,
            String auxType,
            Long auxId,
            LocalDate dateFrom,
            LocalDate dateTo,
            Integer level,
            Long cursor,
            Integer limit) {}
}
