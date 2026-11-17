package cn.erp.fulfillment;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.InventoryTraceQueries;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.inventory.InventoryModels.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class InventorySerialSigningIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryService inventory;
    @Autowired ShipmentService shipments;
    @Autowired InventoryTraceQueries trace;
    private Sample sample;

    @BeforeTransaction void seed(){sample=stockAndOrder();}

    @AfterTransaction void cleanup(){
        long tenant=sample.actor().tenantId();
        for(String table:List.of("inv_movement_hold","doc_line_serial","inv_movement_serial","inv_serial","inventory_movement","inv_batch",
                "inventory_cost","inventory_balance","sales_order_line","sales_order","document_sequence","unit_conversion","sku","product",
                "product_category","unit","partner_role","business_partner","accounting_period","warehouse","company")){
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test void cancellationReleasesSerialsAndOnlyActuallySignedSerialsAppearInCustomerTrace(){
        long cancelled=shipment(sample);
        shipments.submit(sample.actor(),cancelled,key(),0);
        assertThat(status(sample)).containsOnly("reserved");
        shipments.voidSubmitted(sample.actor(),cancelled,key(),1);
        assertThat(status(sample)).containsOnly("in_stock");
        long shipped=shipment(sample);
        shipments.submit(sample.actor(),shipped,key(),0);
        shipments.post(sample.actor(),shipped,key(),1);
        long line=jdbc.queryForObject("SELECT id FROM shipment_line WHERE shipment_id=?",Long.class,shipped);
        assertThat(status(sample)).containsOnly("shipped");
        assertThat(trace.customer(sample.actor(),sample.customer(),sample.sku(),null,10).get("items"))
                .isEqualTo(List.of());
        assertThatThrownBy(()->shipments.sign(sample.actor(),shipped,key(),new ShipmentModels.Signed(2L,sample.day(),
                List.of(new ShipmentModels.SignedLine(line,"1")),List.of())))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo("VALIDATION_FAILED"));
        shipments.sign(sample.actor(),shipped,key(),new ShipmentModels.Signed(2L,sample.day(),
                List.of(new ShipmentModels.SignedLine(line,"1",List.of("SN-A"))),List.of()));
        var received=trace.customer(sample.actor(),sample.customer(),sample.sku(),null,10);
        assertThat(received.get("items")).isInstanceOfSatisfying(List.class,rows->assertThat(rows).singleElement().satisfies(row->{
            assertThat(row).isInstanceOfSatisfying(Map.class,detail->{
                assertThat(detail.get("serials")).isEqualTo(List.of("SN-A"));
                assertThat(detail.get("confirmedQty")).isEqualTo("1.000000");
                assertThat(detail.get("batchNo")).isEqualTo("签收批次");
            });
        }));
        shipments.resolve(sample.actor(),shipped,key(),new ShipmentModels.Resolution(3L,sample.day(),
                List.of(new ShipmentModels.ResolveLine(line,"returned","客户拒收未签收的序列号"))));
        assertThat(status(sample)).containsExactly("shipped","returned");
        var balance=inventory.balance(sample.actor(),new Key(sample.warehouse(),sample.sku()));
        assertThat(balance.onHand()).isEqualByComparingTo("1");
        assertThat(balance.blocked()).isEqualByComparingTo("1");
    }

    private record Sample(CompanyAccess actor,long customer,long warehouse,long sku,long unit,long orderLine,LocalDate day) {}

    private Sample stockAndOrder(){
        long tenant=Ids.next(),company=Ids.next(),warehouse=Ids.next(),sku=Ids.next(),unit=Ids.next(),customer=Ids.next();
        long category=Ids.next(),product=Ids.next(),period=Ids.next(),order=Ids.next(),line=Ids.next(),source=Ids.next();
        var day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var month=YearMonth.from(day);
        var actor=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("cost:view","serial:trace"));
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'序列签收验收')",tenant,"SN"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','公司')",company,tenant);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','成品仓')",warehouse,tenant,company);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'C','客户')",customer,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'customer')",Ids.next(),tenant,customer);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,batch_managed,serial_managed) VALUES(?,?,?,'SKU','序列商品',1,1)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",period,tenant,company,
                month.format(DateTimeFormatter.ofPattern("uuuuMM")),month.atDay(1),month.atEndOfMonth());
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,status,revision,approved_at,
                price_tax_inclusive,pricing_version,goods_amount,total_amount,untaxed_amount,tax_amount)
                VALUES(?,?,?,'SO-SN',?,?,'approved',1,UTC_TIMESTAMP(3),1,2,20,20,20,0)
                """,order,tenant,company,customer,day);
        jdbc.update("""
                INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,
                    base_qty,unit_price,amount,line_discount,line_net,order_discount_alloc,receivable_net,tax_rate,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,1,?,?,?,1,2,2,10,20,0,20,0,20,0,20,0)
                """,line,tenant,company,order,sku,warehouse,unit);
        inventory.receive(actor,new Posting(new Key(warehouse,sku),new Source("opening_entry",source,source),day,period,"opening_in"),
                new BigDecimal("2"),new BigDecimal("20.00"),new StockSelection(null,"签收批次",null,day.plusDays(10),null,List.of("SN-A","SN-B")),null);
        return new Sample(actor,customer,warehouse,sku,unit,line,day);
    }

    private long shipment(Sample sample){
        var input=new ShipmentModels.Input(sample.customer(),sample.warehouse(),sample.day(),"delivery",null,
                List.of(new ShipmentModels.InputLine(sample.orderLine(),sample.unit(),"2")),null);
        return Long.parseLong(shipments.create(sample.actor(),key(),input).get("id").toString());
    }

    private List<String> status(Sample sample){
        return jdbc.queryForList("SELECT status FROM inv_serial WHERE tenant_id=? AND company_id=? AND sku_id=? ORDER BY serial_no",
                String.class,sample.actor().tenantId(),sample.actor().companyId(),sample.sku());
    }

    private String key(){return UUID.randomUUID().toString();}
}
