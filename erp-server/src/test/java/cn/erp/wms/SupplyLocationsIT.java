package cn.erp.wms;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reporting.ReportQuery;
import cn.erp.reporting.ReportService;
import cn.erp.reporting.ReportType;
import cn.erp.shared.ApiException;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.Code128Reader;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class SupplyLocationsIT {
    private static final long TENANT=70101, COMPANY=70201, WAREHOUSE=70301, SKU=71001;
    private final CompanyAccess actor=new CompanyAccess(TENANT,1,COMPANY,true,Set.of(),
            Set.of(ReportType.INVENTORY_SUMMARY.permission(),"cost:view"));
    @Autowired LocationService locations;
    @Autowired ReconciliationChecks reconciliation;
    @Autowired ReportService reports;
    @Autowired JdbcTemplate jdbc;

    @Test
    void openingKeepsWarehouseTotalsAndReconcilesPhysicalStock() {
        var slot=receivingSlot();
        var id=slot.get("id").toString();
        var input=new LocationService.WarehouseInput(id,id,0);
        assertThatThrownBy(()->locations.configure(actor,WAREHOUSE,input)).isInstanceOf(ApiException.class)
                .hasMessageContaining("预留");
    }

    @Test
    void movesLegacyFrozenStockAndDoesNotDoubleCountReports() {
        var id=receivingSlot().get("id").toString();
        jdbc.update("UPDATE inventory_balance SET reserved_qty=0 WHERE id=71901");
        jdbc.update("INSERT INTO inv_block_detail(balance_id,tenant_id,company_id,reason,source_type,source_id,source_line_id,qty) VALUES(71901,70101,70201,'legacy','migration',0,0,1)");
        jdbc.update("UPDATE inventory_movement SET movement_type='opening_in',on_hand_delta=10,blocked_delta=1,cost_delta=100 WHERE id=72301");
        jdbc.update("INSERT INTO catalog_brand(id,tenant_id,code,name) VALUES(71199,70101,'BRAND','测试品牌')");
        jdbc.update("UPDATE sku SET brand_id=71199 WHERE tenant_id=? AND id=?",TENANT,SKU);
        locations.configure(actor,WAREHOUSE,new LocationService.WarehouseInput(id,id,0));
        assertThat(locations.balances(actor,WAREHOUSE,Long.valueOf(id),SKU,null,50)).singleElement()
                .satisfies(row->assertThat(row).containsEntry("onHandQty","10.000000").containsEntry("blockedQty","1.000000"));
        for(String type:List.of("inventory_balance","inventory_cost","stock_blocks","batch_balance","location_balance"))
            assertThat(reconciliation.check(actor,type,null)).as(type).isEmpty();
        var query=new ReportQuery(null,null,WAREHOUSE,SKU,null,null,false,false,null,"brand");
        assertThat(reports.snapshot(actor,ReportType.INVENTORY_SUMMARY,query).items()).singleElement()
                .satisfies(row->assertThat(row).containsEntry("brandName","测试品牌").containsEntry("onHandQty","10.000000"));
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=9,blocked_qty=0 WHERE tenant_id=? AND location_key=?",TENANT,id);
        for(String type:List.of("stock_blocks","batch_balance","location_balance"))
            assertThat(reconciliation.check(actor,type,null)).as(type).isNotEmpty();
    }

    @Test
    void generatedLabelsAreScannableAndBoundToCompanyAndWarehouse() throws Exception {
        var slot=receivingSlot();
        long id=Long.parseLong(slot.get("id").toString());
        var otherCompany=new CompanyAccess(TENANT,1,70202,true,Set.of(),Set.of());
        var restricted=new CompanyAccess(TENANT,1,COMPANY,false,Set.of(),Set.of());
        assertThatThrownBy(()->locations.layout(otherCompany,WAREHOUSE)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(()->locations.label(restricted,WAREHOUSE,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        var foreignTenant=new CompanyAccess(70102,1,70203,true,Set.of(),Set.of());
        assertThatThrownBy(()->locations.label(foreignTenant,WAREHOUSE,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        var label=locations.label(actor,WAREHOUSE,id);
        var image=ImageIO.read(new ByteArrayInputStream(LocationController.barcode(label)));
        int width=image.getWidth(),height=image.getHeight();
        int[] pixels=image.getRGB(0,0,width,height,null,0,width);
        var data=new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width,height,pixels)));
        assertThat(new Code128Reader().decode(data).getText()).isEqualTo("RC-01-01-01-01");
    }

    private java.util.Map<String,Object> receivingSlot() {
        var zone=locations.saveZone(actor,WAREHOUSE,new LocationService.ZoneInput(null,"RC","收货区","receiving",true,null));
        assertThat(locations.generate(actor,WAREHOUSE,new LocationService.BatchInput(zone.get("id").toString(),"RC",1,1,1,2)))
                .containsEntry("count",2);
        var layout=locations.layout(actor,WAREHOUSE);
        if(layout.get("locations") instanceof List<?> rows&&rows.getFirst() instanceof java.util.Map<?,?> row)
            return locations.label(actor,WAREHOUSE,Long.parseLong(row.get("id").toString()));
        throw new AssertionError("库位未生成");
    }
}
