package cn.erp.purchasefees;

import org.junit.jupiter.api.Test;
import cn.erp.shared.ApiException;
import static org.assertj.core.api.Assertions.*;

class PurchaseFeeWriterTest {
    @Test void inclusiveFeeSplitsVatAndAcceptsLoading() {
        var fee=PurchaseFeeWriter.calculate(new PurchaseFeeModels.FeeInput("loading","113.00","0.13",null));
        assertThat(fee.amount()).isEqualByComparingTo("113");
        assertThat(fee.tax()).isEqualByComparingTo("13");
    }
    @Test void rejectsMalformedAmountsRatesAndUnknownTypes() {
        for(var input:java.util.List.of(new PurchaseFeeModels.FeeInput("other","1.001","0",null),new PurchaseFeeModels.FeeInput("other","0","0",null),
                new PurchaseFeeModels.FeeInput("other","1","1",null),new PurchaseFeeModels.FeeInput("other","1","0.0000001",null),
                new PurchaseFeeModels.FeeInput("unknown","1","0",null),new PurchaseFeeModels.FeeInput("freight","1x","0",null),
                new PurchaseFeeModels.FeeInput("other","10000000000000000000000","0",null)))
            assertThatThrownBy(()->PurchaseFeeWriter.calculate(input)).isInstanceOf(ApiException.class);
    }
}
