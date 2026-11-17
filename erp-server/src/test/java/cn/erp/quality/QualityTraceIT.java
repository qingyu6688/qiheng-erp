package cn.erp.quality;

import cn.erp.ErpApplication;
import cn.erp.fulfillment.ShipmentModels;
import cn.erp.fulfillment.ShipmentService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.InventoryTraceQueries;
import cn.erp.receiving.ReceivingModels;
import cn.erp.receiving.ReceivingService;
import cn.erp.salesorder.SalesOrderModels;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static cn.erp.quality.QualityModels.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class QualityTraceIT {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    @Autowired JdbcTemplate jdbc;
    @Autowired ReceivingService receiving;
    @Autowired QualitySettings settings;
    @Autowired QualityInspections inspections;
    @Autowired InventoryService inventory;
    @Autowired InventoryTraceQueries trace;
    @Autowired SalesOrderService sales;
    @Autowired ShipmentService shipments;
    private long tenant,company,otherCompany,warehouse,otherWarehouse,sku,unit,supplier,customer,bin,item;
    private LocalDate day;
    private CompanyAccess operator,reviewer;

    @BeforeEach void seed() {
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();warehouse=Ids.next();otherWarehouse=Ids.next();
        sku=Ids.next();unit=Ids.next();supplier=Ids.next();customer=Ids.next();bin=Ids.next();item=Ids.next();
        long category=Ids.next(),product=Ids.next(),zone=Ids.next(),template=Ids.next();
        day=LocalDate.now(ZONE);
        var month=YearMonth.from(day);
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'来料追溯验收')",tenant,"QC"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','来料追溯公司'),(?,?,'B','另一公司')",company,tenant,otherCompany,tenant);
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",Ids.next(),tenant,company);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W1','来料仓'),(?,?,?,'W2','另一个仓')",warehouse,tenant,company,otherWarehouse,tenant,company);
        jdbc.update("INSERT INTO wms_zone(id,tenant_id,company_id,warehouse_id,code,name,type) VALUES(?,?,?,?,'Q','待检区','quarantine')",zone,tenant,company,warehouse);
        jdbc.update("INSERT INTO wms_location(id,tenant_id,company_id,warehouse_id,zone_id,code,name) VALUES(?,?,?,?,?,'Q01','待检位')",bin,tenant,company,warehouse,zone);
        jdbc.update("UPDATE warehouse SET location_managed=1,default_receiving_location_id=?,default_shipping_location_id=? WHERE id=?",bin,bin,warehouse);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'SUP','追溯供应商'),(?,?,'CUS','追溯客户')",supplier,tenant,customer,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier'),(?,?,?,'customer')",Ids.next(),tenant,supplier,Ids.next(),tenant,customer);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate,batch_managed,issue_strategy) VALUES(?,?,?,'SKU','追溯批次商品',0,1,'fefo')",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",Ids.next(),tenant,company,
                month.toString().replace("-",""),month.atDay(1),month.atEndOfMonth());
        jdbc.update("INSERT INTO qc_item(id,tenant_id,code,name,type) VALUES(?,?,'VISUAL','外观','qualitative')",item,tenant);
        jdbc.update("INSERT INTO qc_template(id,tenant_id,code,name,sampling) VALUES(?,?,'IQC','来料标准','full')",template,tenant);
        jdbc.update("INSERT INTO qc_template_item(tenant_id,template_id,item_id,seq_no,standard_value) VALUES(?,?,?,1,'无破损')",tenant,template,item);
        var permissions=new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission",String.class));
        operator=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),permissions);
        reviewer=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),permissions);
        settings.rule(operator,null,new Rule("sku",Long.toString(sku),Long.toString(supplier),Long.toString(template),true,"active",null));
    }

    @Test void inspectPartiallyReturnAndTraceSignedCustomerWithoutReleasingAnotherReceipt() {
        long first=receive("5"),second=receive("4");
        long firstInspection=inspection(first),secondInspection=inspection(second);
        long batch=jdbc.queryForObject("SELECT batch_id FROM goods_receipt_line WHERE receipt_id=?",Long.class,first);
        assertThat(inventory.balance(operator,new Key(warehouse,sku)).available()).isZero();
        assertThat(inventory.balance(operator,new Key(warehouse,sku)).blocked()).isEqualByComparingTo("9");
        var result=inspections.submit(operator,firstInspection,key(),new Submission(0L,"3","2","0",null,
                List.of(new Value(Long.toString(item),"外箱局部破损","ng")),List.of(new Defect("appearance","2","外箱破损"))));
        assertThat(result).containsEntry("status","submitted");
        assertCode(()->inspections.decide(operator,firstInspection,"approve",key(),new Decision(version(result),null)),"SAME_OPERATOR_NOT_ALLOWED");
        var approved=inspections.decide(reviewer,firstInspection,"approve",key(),new Decision(version(result),null));
        assertThat(approved).containsEntry("result","partial").containsEntry("remainingQty","2.000000");
        assertThat(inventory.balance(operator,new Key(warehouse,sku)).available()).isEqualByComparingTo("3");
        assertThat(inventory.balance(operator,new Key(warehouse,sku)).blocked()).isEqualByComparingTo("6");
        var disposed=inspections.dispose(operator,firstInspection,key(),new Disposition(version(approved),"return","2","供应商退货"));
        var draft=(Map<?,?>)((List<?>)disposed.get("dispositions")).getFirst();
        assertThat(draft.get("targetType")).isEqualTo("purchase_return");
        long returnId=Long.parseLong(draft.get("targetId").toString());
        receiving.command(operator,true,returnId,"submit",key(),0L);
        receiving.command(operator,true,returnId,"post",key(),1L);
        assertThat(inventory.balance(operator,new Key(warehouse,sku)).blocked()).isEqualByComparingTo("4");
        assertThat(inspections.detail(operator,secondInspection)).containsEntry("status","draft");
        long shipment=ship("2");
        var forward=rows(trace.batch(operator,batch,null,50));
        assertThat(forward).anySatisfy(row->{
            assertThat(row).containsEntry("sourceId",Long.toString(first)).containsEntry("supplierName","追溯供应商");
            assertThat(rows(row,"inspections")).anySatisfy(q->assertThat(q).containsEntry("id",Long.toString(firstInspection)));
        });
        assertThat(forward).anySatisfy(row->assertThat(row).containsEntry("sourceId",Long.toString(shipment)).containsEntry("customerName","追溯客户"));
        assertThat(rows(trace.customer(operator,customer,sku,null,50))).singleElement()
                .satisfies(row->assertThat(row).containsEntry("batchId",Long.toString(batch)).containsEntry("sourceId",Long.toString(shipment)));
        assertThat(inventory.balance(operator,new Key(warehouse,sku)).available()).isEqualByComparingTo("1");

        var otherCompanyActor=new CompanyAccess(tenant,operator.userId(),otherCompany,true,Set.of(),operator.permissions());
        var otherWarehouseActor=new CompanyAccess(tenant,operator.userId(),company,false,Set.of(otherWarehouse),operator.permissions());
        var otherTenantActor=new CompanyAccess(Ids.next(),operator.userId(),company,true,Set.of(),operator.permissions());
        var selfOnly=new CompanyAccess(tenant,reviewer.userId(),company,true,Set.of(),operator.permissions(),OrgScope.restricted(Set.of(),Set.of(reviewer.userId()),0));
        for(var scope:List.of(otherCompanyActor,otherWarehouseActor,otherTenantActor,selfOnly)){
            assertCode(()->inspections.detail(scope,firstInspection),"NOT_FOUND");
            assertThat(rows(inspections.list(scope,null,null,null,50,false))).isEmpty();
            assertCode(()->trace.batch(scope,batch,null,50),"NOT_FOUND");
            assertThat(rows(trace.customer(scope,customer,sku,null,50))).isEmpty();
        }
    }

    private long receive(String quantity){
        long order=Ids.next(),line=Ids.next();
        var qty=new java.math.BigDecimal(quantity);
        var amount=qty.multiply(java.math.BigDecimal.TEN);
        jdbc.update("""
                INSERT INTO purchase_order(id,tenant_id,company_id,order_no,supplier_id,business_date,price_tax_inclusive,
                  goods_amount,total_amount,untaxed_amount,tax_amount,status,revision,approved_at,owner_user_id)
                VALUES(?,?,?,?,?,?,1,?,?,?,0,'approved',1,UTC_TIMESTAMP(3),?)
                """,order,tenant,company,"PO-"+order,supplier,day,amount,amount,amount,operator.userId());
        jdbc.update("""
                INSERT INTO purchase_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,
                  receive_limit_base_qty,unit_price,amount,line_net,payable_net,tax_rate,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,1,?,?,?,1,?,?,?,10,?,?,?,0,?,0)
                """,line,tenant,company,order,sku,warehouse,unit,qty,qty,qty,amount,amount,amount,amount);
        var selection=new StockSelection(null,"IQC-BATCH",day.minusDays(1),day.plusDays(10),bin,List.of());
        var input=new ReceivingModels.ReceiptInput(supplier,warehouse,day,null,List.of(new ReceivingModels.ReceiptLine(line,unit,quantity,selection)),null);
        long receipt=docId(receiving.createReceipt(operator,key(),input));
        receiving.command(operator,false,receipt,"submit",key(),0L);
        receiving.command(operator,false,receipt,"post",key(),1L);
        return receipt;
    }

    private long ship(String quantity){
        var input=new SalesOrderModels.Input(Long.toString(customer),day,null,true,"0",
                List.of(new SalesOrderModels.Line(null,Long.toString(sku),Long.toString(warehouse),Long.toString(unit),quantity,"20",null,null,null,"0")),
                List.of(),null,null,null);
        var draft=sales.create(operator,key(),input);
        long order=docId(draft);
        var pending=sales.command(operator,order,"submit",key(),new SalesOrderModels.Command(version(draft),null,null));
        sales.command(reviewer,order,"approve",key(),new SalesOrderModels.Command(version(pending),null,null));
        long source=jdbc.queryForObject("SELECT id FROM sales_order_line WHERE order_id=? AND is_deleted=0",Long.class,order);
        long shipment=docId(shipments.create(operator,key(),new ShipmentModels.Input(customer,warehouse,day,"delivery",null,
                List.of(new ShipmentModels.InputLine(source,unit,quantity)),null)));
        shipments.submit(operator,shipment,key(),0);
        shipments.post(operator,shipment,key(),1);
        long line=jdbc.queryForObject("SELECT id FROM shipment_line WHERE shipment_id=?",Long.class,shipment);
        shipments.sign(operator,shipment,key(),new ShipmentModels.Signed(2L,day,List.of(new ShipmentModels.SignedLine(line,quantity)),List.of()));
        return shipment;
    }

    private long inspection(long receipt){return jdbc.queryForObject("SELECT id FROM qc_inspection WHERE tenant_id=? AND source_id=?",Long.class,tenant,receipt);}
    private static long docId(Map<String,Object> row){return Long.parseLong(row.get("id").toString());}
    private static long version(Map<String,Object> row){return ((Number)row.get("version")).longValue();}
    private static String key(){return UUID.randomUUID().toString();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> row,String key){return (List<Map<String,Object>>)row.get(key);}
    private static List<Map<String,Object>> rows(Map<String,Object> row){return rows(row,"items");}
    private static void assertCode(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));}
}
