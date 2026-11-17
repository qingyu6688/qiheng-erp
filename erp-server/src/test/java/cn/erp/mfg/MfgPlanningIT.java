package cn.erp.mfg;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.mfg.MfgPlanningModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class MfgPlanningIT {
    private static final LocalDate DAY=LocalDate.of(2026,11,17);
    @Autowired JdbcTemplate jdbc;
    @Autowired MfgBomService boms;
    @Autowired MfgWorkOrderService orders;
    private long tenant,company,warehouse,secondary,department,piece,pack,product,finished,semi,raw,alternate;
    private CompanyAccess maker;

    @BeforeTransaction void seedCompany() {
        tenant=Ids.next(); company=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'生产计划验收企业')",tenant,"MF"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'P','生产计划验收公司')",company,tenant);
    }

    @AfterTransaction void cleanCompany() {
        jdbc.update("DELETE FROM document_sequence WHERE tenant_id=? AND company_id=?",tenant,company);
        jdbc.update("DELETE FROM company WHERE tenant_id=? AND id=?",tenant,company);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @BeforeEach void seed() {
        warehouse=Ids.next(); secondary=Ids.next(); department=Ids.next();
        piece=Ids.next(); pack=Ids.next(); product=Ids.next();
        maker=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("sales_order:view","inventory:view"),
                OrgScope.restricted(Set.of(department),Set.of(),department));
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W1','总装仓'),(?,?,?,'W2','原料仓')",
                warehouse,tenant,company,secondary,tenant,company);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件'),(?,?,'包')",piece,tenant,pack,tenant);
        long group=Ids.next();
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','生产测试分类','/')",group,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','生产测试产品',?)",product,tenant,group,piece);
        finished=sku("FG"); semi=sku("HALF"); raw=sku("RAW"); alternate=sku("ALT");
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,2,0)",Ids.next(),tenant,raw,pack);
    }

    @Test void expandsPhantomAndFreezesUnitSubstitutionAndWarehousePermissions() {
        approved(draft(semi,"S1","2",DAY,null,true,List.of(
                new BomLine(raw,pack,"1.25","10",secondary,false,null,List.of(new Substitute(alternate,piece,"6",1))))));
        var finishedDraft=draft(finished,"F1","1",DAY,null,true,List.of(
                new BomLine(semi,piece,"2","0",null,true,null,List.of()),line(semi,"1",warehouse)));
        long bom=approved(finishedDraft);
        var preview=rows(boms.explode(maker,bom,"3",DAY,warehouse),"lines");
        assertThat(preview).anySatisfy(row->assertThat(row).containsEntry("componentSkuId",Long.toString(raw))
                .containsEntry("requiredQty","8.250000").containsEntry("depth",2).containsEntry("availableQty","0.000000"));

        var input=new WorkOrderDraft(finished,bom,piece,"3",warehouse,warehouse,DAY,DAY,"5",null,null);
        long id=id(orders.create(maker,key(),input));
        var restricted=new CompanyAccess(tenant,maker.userId(),company,false,Set.of(warehouse),maker.permissions(),maker.orgScope());
        assertThatThrownBy(()->orders.command(restricted,id,"release",key(),new Revision(0L))).isInstanceOf(ApiException.class);
        var released=orders.command(maker,id,"release",key(),new Revision(0L));
        assertThat(released).containsEntry("status","released");
        var frozen=rows(released,"materials");
        assertThat(frozen).hasSize(2);
        var first=frozen.getFirst();
        assertThat(first).containsEntry("componentSkuId",Long.toString(raw)).containsEntry("unitId",Long.toString(piece))
                .containsEntry("issueWarehouseId",Long.toString(secondary)).containsEntry("requiredQty","8.250000");
        assertThat(rows(first,"substitutes")).singleElement().satisfies(row->assertThat(row).containsEntry("ratio","3.000000"));
        assertThat(frozen.getLast()).containsEntry("componentSkuId",Long.toString(semi)).containsEntry("requiredQty","3.000000");

        boms.command(maker,bom,"disable",key(),new Revision(2L));
        assertThat(rows(orders.detail(maker,id),"materials")).isEqualTo(frozen);
        assertThatThrownBy(()->orders.detail(restricted,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        assertThat(rows(orders.list(restricted,null,null,null,null,30),"items")).isEmpty();
        var otherDept=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of(),
                OrgScope.restricted(Set.of(Ids.next()),Set.of(),0));
        assertThatThrownBy(()->boms.detail(otherDept,bom)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(()->orders.detail(otherDept,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        for(var foreign:List.of(new CompanyAccess(Ids.next(),maker.userId(),company,true,Set.of(),Set.of()),
                new CompanyAccess(tenant,maker.userId(),Ids.next(),true,Set.of(),Set.of()))) {
            assertThat(rows(orders.list(foreign,null,null,null,null,30),"items")).isEmpty();
            assertThatThrownBy(()->boms.detail(foreign,bom)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        }
        approved(draft(alternate,"精度-子件","1",DAY,null,true,List.of(line(raw,"3",secondary))));
        long accuracy=approved(draft(finished,"精度-成品","3",DAY,null,true,List.of(
                new BomLine(alternate,piece,"2","0",null,true,null,List.of()))));
        assertThat(rows(boms.explode(maker,accuracy,"1",DAY,warehouse),"lines")).anySatisfy(row->assertThat(row)
                .containsEntry("componentSkuId",Long.toString(raw)).containsEntry("requiredQty","2.000000"));
    }

    @Test void refusesCyclesAndOverlappingDefaultsAndRetainsPointInTimeVersions() {
        approved(draft(semi,"A","1",DAY,DAY.plusDays(5),true,List.of(line(raw,"1",null))));
        assertThatThrownBy(()->boms.create(maker,key(),draft(raw,"循环","1",DAY,DAY.plusDays(2),true,List.of(line(semi,"1",null)))))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getMessage()).contains("循环引用"));
        long duplicate=id(boms.create(maker,key(),draft(semi,"重复","1",DAY.plusDays(4),null,true,List.of(line(raw,"1",null)))));
        boms.command(maker,duplicate,"submit",key(),new Revision(0L));
        assertThatThrownBy(()->boms.command(maker,duplicate,"approve",key(),new Revision(1L)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getMessage()).contains("生效日期"));
        long next=approved(draft(semi,"B","1",DAY.plusDays(6),null,true,List.of(line(raw,"3",null))));
        var order=orders.create(maker,key(),new WorkOrderDraft(semi,null,piece,"1",warehouse,warehouse,
                DAY.plusDays(6),DAY.plusDays(7),null,null,null));
        assertThat(order).containsEntry("bomId",Long.toString(next));
    }

    @Test void salesShortageIsScopedAndDoesNotCreateRepeatedProductionDemand() {
        long bom=approved(draft(finished,"销售","1",DAY,null,true,List.of(line(raw,"1",null))));
        long customer=Ids.next(), order=Ids.next(), salesLine=Ids.next();
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'C','销售转生产验收客户')",customer,tenant);
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,status,revision,approved_at,
                    price_tax_inclusive,goods_amount,total_amount,untaxed_amount,owner_user_id,owner_department_id)
                VALUES(?,?,?,'SO-MFG',?,?,'approved',1,UTC_TIMESTAMP(3),1,100,100,100,?,?)
                """,order,tenant,company,customer,DAY,maker.userId(),department);
        jdbc.update("""
                INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,
                    base_qty,unit_price,amount,line_net,receivable_net,tax_rate,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,1,?,?,?,1,10,10,10,100,100,100,0,100,0)
                """,salesLine,tenant,company,order,finished,warehouse,piece);
        var input=new FromSalesOrder(order,salesLine,bom,warehouse,warehouse,DAY,DAY,null,null);
        var forbidden=new CompanyAccess(tenant,maker.userId(),company,true,Set.of(),Set.of(),maker.orgScope());
        assertThatThrownBy(()->orders.fromSalesOrder(forbidden,key(),input)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
        String request=key();
        var draft=orders.fromSalesOrder(maker,request,input);
        assertThat(draft).containsEntry("sourceLineId",Long.toString(salesLine)).containsEntry("planQty","10.000000");
        assertThat(orders.fromSalesOrder(maker,request,input).get("id")).isEqualTo(draft.get("id"));
        assertThatThrownBy(()->orders.fromSalesOrder(maker,key(),input)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getMessage()).contains("暂无"));
    }

    private long sku(String code) {
        long sku=Ids.next();
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(?,?,?,?,?)",sku,tenant,product,code,code);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,piece);
        return sku;
    }

    private BomLine line(long sku,String quantity,Long warehouse) {
        return new BomLine(sku,piece,quantity,"0",warehouse,false,null,List.of());
    }

    private BomDraft draft(long sku,String version,String base,LocalDate start,LocalDate end,boolean main,List<BomLine> lines) {
        return new BomDraft(sku,version,piece,base,start,end,main,null,null,lines);
    }

    private long approved(BomDraft draft) {
        long bom=id(boms.create(maker,key(),draft));
        boms.command(maker,bom,"submit",key(),new Revision(0L));
        boms.command(maker,bom,"approve",key(),new Revision(1L));
        return bom;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> row,String key) {return (List<Map<String,Object>>)row.get(key);}
    private static long id(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
    private static String key() {return UUID.randomUUID().toString();}
}
