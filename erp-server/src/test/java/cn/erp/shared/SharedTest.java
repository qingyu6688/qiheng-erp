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
        assertThat(ConstraintErrors.from("unknown").getStatus()).isEqualTo(500);
    }
}
