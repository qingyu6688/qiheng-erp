package cn.erp.shared;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SharedTest {
    @Test
    void preservesDatabasePrecision() {
        assertThat(new Money(new BigDecimal("1")).value().toPlainString()).isEqualTo("1.00");
        assertThat(new Quantity(new BigDecimal("1.000001")).value().scale()).isEqualTo(6);
        assertThatThrownBy(() -> new Money(new BigDecimal("0.001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Quantity(new BigDecimal("1E+20"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentIdsAreUnique() {
        var ids = ConcurrentHashMap.<Long>newKeySet();
        IntStream.range(0, 20_000).parallel().forEach(i -> ids.add(Ids.next()));
        assertThat(ids).hasSize(20_000);
        long first = Ids.next();
        assertThat(Ids.next()).isGreaterThan(first);
    }

    @Test
    void mapsOnlyRecognisedDatabaseConstraints() {
        assertThat(ConstraintErrors.from("Duplicate entry 'A' for key 'warehouse.uk_warehouse_code'").getStatus()).isEqualTo(409);
        assertThat(ConstraintErrors.from("Check constraint 'ck_shipment_line_sign' is violated").getErrorCode()).isEqualTo("SIGN_QTY_EXCEEDED");
        assertThat(ConstraintErrors.from("Duplicate entry '1' for key 'invoice_red_registration.uk_invoice_red_active'").getErrorCode()).isEqualTo("INVOICE_RED_EXISTS");
        assertThat(ConstraintErrors.from("Duplicate entry 'RED' for key 'invoice_red_registration.uk_invoice_red_number'").getErrorCode()).isEqualTo("DUPLICATE");
        assertThat(ConstraintErrors.from("Check constraint 'ck_invoice_red_review' is violated").getErrorCode()).isEqualTo("TWO_PERSON_REQUIRED");
        assertThat(ConstraintErrors.from("unknown").getStatus()).isEqualTo(500);
    }

    @Test
    void mysqlCheckViolationsUseTheSameFriendlyErrorMapping() {
        var sql=new java.sql.SQLException("Check constraint 'ck_inventory_balance_available' is violated.","HY000",3819);
        var error=new org.springframework.jdbc.UncategorizedSQLException("保存库存","UPDATE inventory_balance",sql);
        var response=new ApiErrors().mysqlCheck(error);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().errorCode()).isEqualTo("INVENTORY_INSUFFICIENT");
    }
}
