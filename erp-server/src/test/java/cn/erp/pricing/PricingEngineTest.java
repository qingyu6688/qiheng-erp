package cn.erp.pricing;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PricingEngineTest {
    private final PricingEngine engine=new PricingEngine();

    @Test
    void R3So4DiscountAndTaxAgreeWithEveryDocumentCent() {
        var preview=engine.calculate(new PricingModels.Calculation(true,"100.00",List.of(
                line("4","115","60",null),line("8","55","40",null),line("2","210","20",null)),
                List.of(new PricingModels.Fee("freight","buyer","30.00","0.13"))));
        assertThat(preview.goodsAmount()).isEqualTo("1320.00");
        assertThat(preview.lineDiscountAmount()).isEqualTo("120.00");
        assertThat(preview.lines()).extracting(PricingModels.LineResult::orderDiscountAlloc)
                .containsExactly("33.34","33.33","33.33");
        assertThat(preview.lines()).extracting(PricingModels.LineResult::taxAmount)
                .containsExactly("42.18","42.18","42.18");
        assertThat(preview.totalAmount()).isEqualTo("1130.00");
        assertThat(preview.untaxedAmount()).isEqualTo("1000.01");
        assertThat(preview.taxAmount()).isEqualTo("129.99");
        BigDecimal net=preview.lines().stream().map(l->new BigDecimal(l.receivableNet())).reduce(BigDecimal.ZERO,BigDecimal::add);
        assertThat(net.add(new BigDecimal(preview.feeAmount()))).isEqualByComparingTo(preview.totalAmount());
    }

    @Test
    void R3CumulativeRecognitionPreservesTailAndDoesNotRecognizeClosedQuantity() {
        var first=engine.recognize("366.67","42.18","8","0","3");
        var second=engine.recognize("366.67","42.18","8","3","6");
        var third=engine.recognize("366.67","42.18","8","6","8");
        assertThat(List.of(first.totalAmount(),second.totalAmount(),third.totalAmount()))
                .containsExactly("137.50","137.50","91.67");
        assertThat(List.of(first.taxAmount(),second.taxAmount(),third.taxAmount()))
                .containsExactly("15.82","15.82","10.54");
        assertThat(new BigDecimal(first.totalAmount()).add(new BigDecimal(second.totalAmount())))
                .isEqualByComparingTo("275.00");
        BigDecimal total=BigDecimal.ZERO;
        for(int i=1;i<=7;i++) {
            total=total.add(new BigDecimal(engine.recognize("0.05","0","7",Integer.toString(i-1),Integer.toString(i)).totalAmount()));
        }
        assertThat(total).isEqualByComparingTo("0.05");
        var tiny=engine.recognize("0.05","0.02","7","1","2");
        assertThat(tiny.totalAmount()).isEqualTo("0.00");
        assertThat(tiny.taxAmount()).isEqualTo("0.01");
        assertThat(tiny.untaxedAmount()).isEqualTo("-0.01");
        assertThatThrownBy(()->engine.recognize("10","1","2","1","3")).isInstanceOf(ApiException.class);
    }

    @Test
    void R3ExclusiveTaxAndSellerFreightKeepAmountsIndependent() {
        var result=engine.calculate(new PricingModels.Calculation(false,"0",List.of(line("2","10",null,"0.10")),
                List.of(new PricingModels.Fee("freight","seller","30","0.13"))));
        assertThat(result.goodsAmount()).isEqualTo("20.00");
        assertThat(result.lines().getFirst().lineDiscountAmount()).isEqualTo("2.00");
        assertThat(result.lines().getFirst().untaxedAmount()).isEqualTo("18.00");
        assertThat(result.lines().getFirst().receivableNet()).isEqualTo("20.34");
        assertThat(result.pricingVersion()).isEqualTo(2);
        assertThat(result.lines().getFirst().priceTaxInclusive()).isFalse();
        assertThat(result.taxAmount()).isEqualTo("2.34");
        assertThat(result.totalAmount()).isEqualTo("20.34");
        assertThat(result.feeAmount()).isEqualTo("0.00");
        assertThat(result.fees().getFirst().included()).isFalse();
    }

    @Test
    void amountInputIsRecalculatedUsingPersistableUnitPrice() {
        var result=engine.calculate(new PricingModels.Calculation(true,null,List.of(
                new PricingModels.Line("1","1",null,"1000000","1",null,"0.01",null,null,"0")),null));
        assertThat(result.lines().getFirst().unitPrice()).isEqualTo("0.00000001");
        assertThat(result.lines().getFirst().amount()).isEqualTo("0.01");
        var warning=engine.calculate(new PricingModels.Calculation(true,null,List.of(
                new PricingModels.Line("1","1",null,"100000000","1",null,"0.01",null,null,"0")),null));
        assertThat(warning.lines().getFirst().amount()).isEqualTo("0.00");
        assertThat(warning.warnings()).singleElement().extracting(PricingModels.Warning::type).isEqualTo("AMOUNT_RECALCULATED");
    }

    @Test
    void T20RejectsInvalidPrecisionOverflowAndUnrepresentableUnitConversion() {
        for(String qty:List.of("-1","0","0.0000001","1000000000000000000","1e3","NaN")) {
            assertThatThrownBy(()->engine.calculate(new PricingModels.Calculation(true,"0",List.of(line(qty,"1",null,null)),null)))
                    .isInstanceOf(ApiException.class);
        }
        for(String price:List.of("1.000000001","10000000000000000","-1")) {
            assertThatThrownBy(()->engine.calculate(new PricingModels.Calculation(true,"0",List.of(line("1",price,null,null)),null)))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(()->engine.calculate(new PricingModels.Calculation(true,"2",List.of(line("1","1",null,null)),null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(()->engine.convertQuantity("0.000001","0.100000")).isInstanceOf(ApiException.class);
        assertThat(engine.convertQuantity("2","12")).isEqualTo("24.000000");
        assertThatThrownBy(()->engine.calculate(new PricingModels.Calculation(false,"0",
                List.of(line("999999999999999999","9999999999999999",null,null)),null))).isInstanceOf(ApiException.class);
    }

    @Test
    void zeroGoodsAndZeroDiscountHaveStableResults() {
        var preview=engine.calculate(new PricingModels.Calculation(true,"0",List.of(line("1","0",null,null)),null));
        assertThat(preview.totalAmount()).isEqualTo("0.00");
        assertThat(preview.orderDiscountAmount()).isEqualTo("0.00");
        assertThat(preview.lines().getFirst().orderDiscountAlloc()).isEqualTo("0.00");
    }

    private PricingModels.Line line(String qty,String price,String discount,String ratio) {
        return new PricingModels.Line("1","1",null,qty,"1",price,null,discount,ratio,"0.13");
    }
}
