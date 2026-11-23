package cn.erp.shared;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PaymentTermsTest {
    @Test void daysAcrossMonthAndLeapYear() {
        assertThat(PaymentTerms.dueDate(LocalDate.of(2024, 2, 28), new PaymentTerms.Term("days", 1)))
                .isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(PaymentTerms.dueDate(LocalDate.of(2026, 1, 31), new PaymentTerms.Term("days", 30)))
                .isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(PaymentTerms.dueDate(LocalDate.of(2026, 1, 31), new PaymentTerms.Term("days", 0)))
                .isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test void monthlyAlwaysMeansNextMonth() {
        assertThat(PaymentTerms.dueDate(LocalDate.of(2024, 1, 31), new PaymentTerms.Term("monthly", 28)))
                .isEqualTo(LocalDate.of(2024, 2, 28));
        assertThat(PaymentTerms.dueDate(LocalDate.of(2026, 12, 1), new PaymentTerms.Term("monthly", 1)))
                .isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test void unspecifiedHasNoDueDate() {
        assertThat(PaymentTerms.dueDate(LocalDate.now(), null)).isNull();
        assertThat(PaymentTerms.dueDate(LocalDate.now(), new PaymentTerms.Term(null, null))).isNull();
    }

    @Test void omittedOptionalFieldsKeepOldCommandPayloadsStable() {
        var json = new tools.jackson.databind.ObjectMapper();
        var sales = new cn.erp.salesorder.SalesOrderModels.Input("1", null, null, true, "0",
                java.util.List.of(), java.util.List.of(), null, null, null);
        var purchase = new cn.erp.purchasing.PurchaseOrderModels.Input("1", null, null, true, "0",
                java.util.List.of(), java.util.List.of(), null, null, null);
        var opening = new cn.erp.opening.OpeningService.Entry("receivable", 1L, null, "D", "10", null, null, null);
        assertThat(json.writeValueAsString(sales)).doesNotContain("paymentTerms", "inheritPaymentTerms");
        assertThat(json.writeValueAsString(purchase)).doesNotContain("paymentTerms", "inheritPaymentTerms");
        assertThat(json.writeValueAsString(opening)).doesNotContain("dueDate");
        var report = new cn.erp.reporting.ReportQuery(null,null,null,null,null,null,false).normalized();
        assertThat(json.writeValueAsString(report)).doesNotContain("agingBasis");
    }

    @Test void rejectsIncompleteAndOutOfRangeTerms() {
        for (var term : new PaymentTerms.Term[]{new PaymentTerms.Term(null, 30),
                new PaymentTerms.Term("days", null), new PaymentTerms.Term("days", -1),
                new PaymentTerms.Term("days", 366), new PaymentTerms.Term("monthly", 0),
                new PaymentTerms.Term("monthly", 29), new PaymentTerms.Term("other", 1)}) {
            assertThatThrownBy(() -> PaymentTerms.validate(term)).isInstanceOf(ApiException.class);
        }
    }
}
