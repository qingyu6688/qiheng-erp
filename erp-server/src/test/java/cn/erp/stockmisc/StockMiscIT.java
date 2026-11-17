package cn.erp.stockmisc;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.inventory.InventoryService;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
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
import static cn.erp.stockmisc.StockMiscModels.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class StockMiscIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired StockMiscService service;
    @Autowired InventoryService inventory;
    @Autowired ReversalService reversals;
    private long tenant,company,otherCompany,warehouse,otherWarehouse,sku,unit,partner,expense,income;
    private LocalDate day;
    private CompanyAccess maker,reviewer;

    @BeforeEach void seed(){
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();warehouse=Ids.next();otherWarehouse=Ids.next();
        sku=Ids.next();unit=Ids.next();partner=Ids.next();expense=Ids.next();income=Ids.next();
        long category=Ids.next(),product=Ids.next();
        day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var month=YearMonth.from(day);
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'其他出入库验收')",tenant,"MISC"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','其他出入库公司'),(?,?,'B','另一公司')",company,tenant,otherCompany,tenant);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W1','业务仓'),(?,?,?,'W2','另一个仓')",warehouse,tenant,company,otherWarehouse,tenant,company);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'PARTNER','业务对象')",partner,tenant);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','业务用品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,'SKU','测试货品',0)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("INSERT INTO income_expense_item(id,tenant_id,item_type,code,name) VALUES(?,?,'expense','EXP','领用支出'),(?,?,'income','INC','赠品收入')",expense,tenant,income,tenant);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",Ids.next(),tenant,company,
                month.toString().replace("-",""),month.atDay(1),month.atEndOfMonth());
        var permissions=new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission",String.class));
        maker=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),permissions);
        reviewer=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),permissions);
    }

    @Test void zeroCostGiftChangesWeightedAverageAndReversalRestoresQuantityAndCost(){
        var purchase=post(create("in","gift","5","10",null));
        assertThat(lines(purchase).getFirst()).containsEntry("amount","50.00");
        assertCode(()->create("in","gift","5","0",null),"VALIDATION_FAILED");
        var gift=post(create("in","gift","5","0","供应商赠品"));
        long giftId=id(gift);
        assertThat(lines(gift).getFirst()).containsEntry("amount","0.00");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).cost()).isEqualByComparingTo("50");
        var draft=create("out","sample","2",null,null);
        var pending=command(draft,"submit",maker);
        assertCode(()->command(pending,"approve",maker),"SAME_OPERATOR_NOT_ALLOWED");
        var posted=command(command(pending,"approve",reviewer),"post",maker);
        assertThat(lines(posted).getFirst()).containsEntry("unitCost","5.00000000").containsEntry("amount","10.00");
        var noCost=new CompanyAccess(tenant,maker.userId(),company,true,Set.of(),Set.of("stock_misc_doc:view"));
        assertThat(lines(service.detail(noCost,id(posted))).getFirst()).doesNotContainKeys("amount","inputUnitCost","unitCost");
        reverse(id(posted));
        reverse(giftId);
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("5");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).cost()).isEqualByComparingTo("50");
        assertThat(service.detail(maker,giftId)).containsEntry("status","reversed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_line WHERE tenant_id=? AND account_code='MISC_STOCK_IN'",Long.class,tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_line WHERE tenant_id=? AND account_code='MISC_STOCK_OUT'",Long.class,tenant)).isEqualTo(2);
    }

    @Test void loanPartialReturnUsesOriginalCostAndPermissionsConstrainDetailAndReport(){
        post(create("in","gift","6","10",null));
        var issue=new Input("out","loan",warehouse,partner,expense,day,day.plusDays(7),null,"业务借出",
                List.of(new Line(sku,unit,"3",null,null,null,null)),null);
        var loan=post(service.create(maker,key(),issue));
        long loanId=id(loan),sourceLine=id(lines(loan).getFirst());
        assertThat(rows(service.list(maker,"out",null,null,50,"",true))).hasSize(1);
        var partial=post(service.create(maker,key(),returnInput(loanId,sourceLine,"2")));
        assertThat(lines(partial).getFirst()).containsEntry("amount","20.00");
        assertThat(service.outstanding(maker,loanId)).singleElement()
                .satisfies(line->assertThat(line).containsEntry("remainingQty","1.000000"));
        assertCode(()->service.create(maker,key(),returnInput(loanId,sourceLine,"2")),"LOAN_RETURN_OVERFLOW");
        assertCode(()->reverse(loanId),"DEPENDENCY_EXISTS");
        var foreign=new CompanyAccess(Ids.next(),maker.userId(),company,true,Set.of(),maker.permissions());
        var companyOnly=new CompanyAccess(tenant,maker.userId(),otherCompany,true,Set.of(),maker.permissions());
        var outsideWarehouse=new CompanyAccess(tenant,maker.userId(),company,false,Set.of(otherWarehouse),maker.permissions());
        var departmentOnly=new CompanyAccess(tenant,reviewer.userId(),company,true,Set.of(),maker.permissions(),OrgScope.restricted(Set.of(999L),Set.of(),999));
        for(var scope:List.of(foreign,companyOnly,outsideWarehouse,departmentOnly)){
            assertCode(()->service.detail(scope,loanId),"NOT_FOUND");
            assertCode(()->service.outstanding(scope,loanId),"NOT_FOUND");
            assertThat(rows(service.list(scope,"out",null,null,50,"",true))).isEmpty();
        }
        post(service.create(maker,key(),returnInput(loanId,sourceLine,"1")));
        assertThat(rows(service.list(maker,"out",null,null,50,"",true))).isEmpty();
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("6");
    }

    @Test void aSerialLoanReturnMustStillBelongToItsOriginalOutboundLine(){
        jdbc.update("UPDATE sku SET serial_managed=1 WHERE tenant_id=? AND id=?",tenant,sku);
        var gift=new Input("in","gift",warehouse,null,income,day,null,null,null,
                List.of(new Line(sku,unit,"2","10",null,null,serials("LOAN-1","LOAN-2"))),null);
        post(service.create(maker,key(),gift));
        var first=serialLoan("LOAN-1","LOAN-2");
        long firstId=id(first),firstLine=id(lines(first).getFirst());
        post(service.create(maker,key(),serialReturn(firstId,firstLine,"LOAN-1")));
        var second=serialLoan("LOAN-1");
        long secondId=id(second),secondLine=id(lines(second).getFirst());

        var mismatched=service.create(maker,key(),serialReturn(firstId,firstLine,"LOAN-1"));
        var ready=command(command(mismatched,"submit",maker),"approve",reviewer);
        assertCode(()->command(ready,"post",maker),"SERIAL_NOT_AVAILABLE");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isZero();
        post(service.create(maker,key(),serialReturn(firstId,firstLine,"LOAN-2")));
        post(service.create(maker,key(),serialReturn(secondId,secondLine,"LOAN-1")));
        assertThat(rows(service.list(maker,"out",null,null,50,"",true))).isEmpty();
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("2");
    }

    private Map<String,Object> serialLoan(String... serials){
        var draft=new Input("out","loan",warehouse,partner,expense,day,day.plusDays(7),null,null,
                List.of(new Line(sku,unit,Integer.toString(serials.length),null,null,null,serials(serials))),null);
        return post(service.create(maker,key(),draft));
    }

    private Input serialReturn(long loan,long line,String serial){
        return new Input("in","loan_return",warehouse,partner,income,day,null,loan,null,
                List.of(new Line(sku,unit,"1",null,null,line,serials(serial))),null);
    }

    private StockSelection serials(String... values){return new StockSelection(null,null,null,null,null,List.of(values));}

    private Input returnInput(long loan,long line,String qty){
        return new Input("in","loan_return",warehouse,partner,income,day,null,loan,null,List.of(new Line(sku,unit,qty,null,null,line,null)),null);
    }

    private Map<String,Object> create(String direction,String purpose,String quantity,String cost,String reason){
        return service.create(maker,key(),new Input(direction,purpose,warehouse,null,direction.equals("in")?income:expense,day,null,null,null,
                List.of(new Line(sku,unit,quantity,cost,reason,null,null)),null));
    }

    private Map<String,Object> post(Map<String,Object> draft){return command(command(command(draft,"submit",maker),"approve",reviewer),"post",maker);}
    private Map<String,Object> command(Map<String,Object> prior,String action,CompanyAccess actor){
        return service.command(actor,id(prior),action,key(),new Command(version(prior),null));
    }

    private void reverse(long original){
        var submitted=reversals.create(maker,key(),new ReversalModels.Input("stock_misc_doc",original,day,"库存数据更正"));
        var ready=reversals.command(reviewer,id(submitted),"approve",key(),new ReversalModels.Version(version(submitted),null));
        reversals.command(maker,id(ready),"post",key(),new ReversalModels.Version(version(ready),null));
    }

    private static long id(Map<String,Object> row){return Long.parseLong(row.get("id").toString());}
    private static long version(Map<String,Object> row){return ((Number)row.get("version")).longValue();}
    private static String key(){return UUID.randomUUID().toString();}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> lines(Map<String,Object> document){return (List<Map<String,Object>>)document.get("lines");}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
    private static void assertCode(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));}
}
