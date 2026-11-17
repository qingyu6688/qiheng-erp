package cn.erp.capacity;

import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.*;
import cn.erp.inventory.InventoryService;
import cn.erp.salesfinance.*;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 只在本机独立容量库准备数据，生产包没有此夹具或控制器。 */
@Component
@Profile("dev & load & !prod & !production")
public class CapacityFixture {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(CapacityFixture.class);
    public record Context(long tenant,long company,long maker,long reviewer,long warehouse,long hotWarehouse,long customer,long unit,long period,long cash,List<Long> skus,List<Long> hotLines,List<Long> refunds,long originalReceipt) {}
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final ReceiptService receipts;
    private final RefundApplicationService refunds;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final String databaseUrl,runId,password,secret;
    private final int skuCount;
    private final boolean resume;
    private final List<Context> contexts=new ArrayList<>();
    public CapacityFixture(JdbcTemplate jdbc,InventoryService inventory,LedgerService ledger,ReceiptService receipts,RefundApplicationService refunds,PasswordEncoder passwords,PlatformTransactionManager manager,
            @Value("${spring.datasource.url}") String databaseUrl,@Value("${ERP_LOAD_RUN_ID:}") String runId,
            @Value("${ERP_LOAD_PASSWORD:}") String password,@Value("${ERP_LOAD_KEY:}") String secret,@Value("${ERP_LOAD_SKUS:20000}") int skuCount,@Value("${ERP_LOAD_RESUME:false}") boolean resume) {
        this.jdbc=jdbc;this.inventory=inventory;this.ledger=ledger;this.receipts=receipts;this.refunds=refunds;this.passwords=passwords;this.transactions=new TransactionTemplate(manager);
        this.databaseUrl=databaseUrl;this.runId=runId;this.password=password;this.secret=secret;this.skuCount=skuCount;this.resume=resume;
    }
    public void guard(String supplied) {
        if(!databaseUrl.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/erp_load_[a-z0-9]+(?:_[a-z0-9]+)*(?:\\?.*)?")||!runId.matches("[a-f0-9]{12}")||password.length()<20||secret.length()<24||!secret.equals(supplied))throw new IllegalStateException("容量验收仅允许本机独立容量库、随机运行编号和临时凭据");
    }
    public List<Context> contexts(){return List.copyOf(contexts);}
    public String tenantCode(long tenant){return jdbc.queryForObject("SELECT code FROM tenant WHERE id=?",String.class,tenant);}
    public CompanyAccess scope(Context context,boolean reviewer){return new CompanyAccess(context.tenant(),reviewer?context.reviewer():context.maker(),context.company(),true,Set.of(),Set.copyOf(jdbc.queryForList("SELECT code FROM sys_permission",String.class)));}
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        guard(secret);if(skuCount<100||skuCount>20000)throw new IllegalStateException("容量SKU数量须为100到20000");
        if(resume){restore();LOG.info("容量测试数据已恢复：{}个公司，复用已有隔离库，测试入口19080",contexts.size());return;}
        if(jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE code LIKE ?",Long.class,"LOAD-"+runId+"%")>0)throw new IllegalStateException("容量库运行编号已使用，请为新一轮准备新库");
        String hash=passwords.encode(password);
        for(int tenantIndex=0;tenantIndex<2;tenantIndex++) {
            long tenant=Ids.next(),maker=Ids.next(),reviewer=Ids.next(),role=Ids.next(),unit=Ids.next(),category=Ids.next(),product=Ids.next(),customer=Ids.next();
            var skus=new ArrayList<Long>();int currentTenant=tenantIndex;
            // 单号使用独立短事务，所以公司等基础数据必须先提交。
            transactions.executeWithoutResult(status->{
            jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"LOAD-"+runId+"-"+currentTenant,"容量隔离企业"+currentTenant);
            jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'LOAD','容量验收')",role,tenant);
            for(long user:List.of(maker,reviewer)) {
                jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,?,?)",user,tenant,user==maker?"operator":"reviewer","容量验收人员",hash);
                jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
            }
            for(long permission:jdbc.queryForList("SELECT id FROM sys_permission",Long.class))jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,role,permission);
            jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
            jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'LOAD','容量商品','/')",category,tenant);
            jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'LOAD','容量商品',?)",product,tenant,category,unit);
            jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'LOAD-CUSTOMER','容量客户')",customer,tenant);
            for(String businessRole:List.of("customer","supplier"))jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,?)",Ids.next(),tenant,customer,businessRole);
            var skuRows=new ArrayList<Object[]>();var conversions=new ArrayList<Object[]>();
            int count=currentTenant==0?skuCount:100;
            for(int index=0;index<count;index++){long sku=Ids.next();skus.add(sku);skuRows.add(new Object[]{sku,tenant,product,"LOAD-"+index,"容量商品"+index});conversions.add(new Object[]{Ids.next(),tenant,sku,unit});}
            jdbc.batchUpdate("INSERT INTO sku(id,tenant_id,product_id,code,name,sale_price,purchase_price,default_tax_rate) VALUES(?,?,?,?,?,10,1,0)",skuRows);
            jdbc.batchUpdate("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",conversions);
            });
            for(int companyIndex=0;companyIndex<2;companyIndex++) {
                long company=Ids.next(),warehouse=Ids.next(),hotWarehouse=Ids.next(),period=Ids.next(),cash=Ids.next();LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));YearMonth month=YearMonth.from(day);
                int currentCompany=companyIndex;
                transactions.executeWithoutResult(status->{
                jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",company,tenant,"C"+currentCompany,"容量公司"+currentCompany);
                jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",Ids.next(),tenant,company);
                for(long user:List.of(maker,reviewer))jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,company);
                for(long wh:List.of(warehouse,hotWarehouse))jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",wh,tenant,company,wh==warehouse?"DISTRIBUTED":"HOT",wh==warehouse?"分散仓":"热点仓");
                jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",period,tenant,company,month.toString().replace("-",""),month.atDay(1),month.atEndOfMonth());
                jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,'LOAD','容量资金','bank')",cash,tenant,company);
                jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,cash);
                });
                var context=new Context(tenant,company,maker,reviewer,warehouse,hotWarehouse,customer,unit,period,cash,List.copyOf(skus.subList(0,100)),new ArrayList<>(),new ArrayList<>(),0);
                CompanyAccess scope=scope(context,false);
                transactions.executeWithoutResult(status->{
                    for(int index=0;index<100;index++)opening(scope,warehouse,skus.get(index),period,day,"100000","100000");
                    opening(scope,hotWarehouse,skus.getFirst(),period,day,"100","100");
                });
                long receipt=number(receipts.create(scope,key(),new FinanceModels.Receipt("customer",customer,null,cash,"100",day,null,null,"容量退款额度",null)),"id");
                receipts.command(scope,receipt,"submit",key(),new FinanceModels.Version(0L,null,null));receipts.command(scope(context,true),receipt,"confirm",key(),new FinanceModels.Version(1L,null,null));
                long credit=jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND company_id=? AND item_type='receipt' AND source_id=?",Long.class,tenant,company,receipt);
                var refundIds=new ArrayList<Long>();for(int index=0;index<100;index++)refundIds.add(number(refunds.create(scope,key(),new FinanceModels.Refund(customer,receipt,cash,"2",day,"容量额度竞争",List.of(credit),null)),"id"));
                int sales=tenantIndex==0&&companyIndex==0?1000:20,purchases=tenantIndex==0&&companyIndex==0?200:10;
                List<Long> hotLines=Objects.requireNonNull(transactions.execute(status->historical(context,sales,purchases,day)));
                contexts.add(new Context(tenant,company,maker,reviewer,warehouse,hotWarehouse,customer,unit,period,cash,context.skus(),hotLines,List.copyOf(refundIds),receipt));
            }
        }
        LOG.info("容量测试数据已准备：{}个公司，主租户{}个SKU，测试入口19080",contexts.size(),skuCount);
    }
    private void restore() {
        for(int index=0;index<2;index++) {
            long tenant=jdbc.queryForObject("SELECT id FROM tenant WHERE code=?",Long.class,"LOAD-"+runId+"-"+index);
            long maker=jdbc.queryForObject("SELECT id FROM sys_user WHERE tenant_id=? AND username='operator'",Long.class,tenant);
            long reviewer=jdbc.queryForObject("SELECT id FROM sys_user WHERE tenant_id=? AND username='reviewer'",Long.class,tenant);
            long unit=jdbc.queryForObject("SELECT id FROM unit WHERE tenant_id=? AND name='件'",Long.class,tenant);
            long customer=jdbc.queryForObject("SELECT id FROM business_partner WHERE tenant_id=? AND code='LOAD-CUSTOMER'",Long.class,tenant);
            var skus=jdbc.queryForList("SELECT id FROM sku WHERE tenant_id=? ORDER BY id LIMIT 100",Long.class,tenant);
            var companies=jdbc.queryForList("SELECT id FROM company WHERE tenant_id=? ORDER BY id",Long.class,tenant);
            if(skus.size()!=100||companies.size()!=2)throw new IllegalStateException("容量库基础数据不完整，不能恢复");
            for(long company:companies) {
                long warehouse=jdbc.queryForObject("SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? AND code='DISTRIBUTED'",Long.class,tenant,company);
                long hotWarehouse=jdbc.queryForObject("SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? AND code='HOT'",Long.class,tenant,company);
                LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
                long period=jdbc.queryForObject("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND start_date<=? AND end_date>=?",Long.class,tenant,company,day,day);
                long cash=jdbc.queryForObject("SELECT id FROM cash_account WHERE tenant_id=? AND company_id=? AND code='LOAD'",Long.class,tenant,company);
                long receipt=jdbc.queryForObject("SELECT id FROM receipt WHERE tenant_id=? AND company_id=? AND remark='容量退款额度'",Long.class,tenant,company);
                var refundIds=jdbc.queryForList("SELECT id FROM refund_request WHERE tenant_id=? AND company_id=? AND original_receipt_id=? ORDER BY id",Long.class,tenant,company,receipt);
                var hot=jdbc.queryForList("SELECT l.id FROM sales_order_line l JOIN sales_order h ON h.id=l.order_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id WHERE h.tenant_id=? AND h.company_id=? AND h.order_no LIKE 'HOT-%' ORDER BY h.id",Long.class,tenant,company);
                if(refundIds.size()!=100||hot.size()!=1000)throw new IllegalStateException("容量库竞争数据不完整，不能恢复");
                contexts.add(new Context(tenant,company,maker,reviewer,warehouse,hotWarehouse,customer,unit,period,cash,List.copyOf(skus),List.copyOf(hot),List.copyOf(refundIds),receipt));
            }
        }
    }
    private void opening(CompanyAccess scope,long warehouse,long sku,long period,LocalDate day,String quantity,String cost) {
        long source=Ids.next();inventory.receive(scope,new Posting(new Key(warehouse,sku),new Source("capacity_opening",source,source),day,period,"opening_in"),new BigDecimal(quantity),new BigDecimal(cost));
        ledger.post(scope,new LedgerService.Posting("capacity_opening",source,0,period,day,List.of(new LedgerService.Line("INVENTORY","D",new BigDecimal(cost),null,null,warehouse,sku,source,"容量库存期初"),new LedgerService.Line("OPENING_EQUITY","C",new BigDecimal(cost),null,null,null,null,source,"容量库存期初"))));
    }
    private List<Long> historical(Context context,int salesCount,int purchaseCount,LocalDate day) {
        var headers=new ArrayList<Object[]>();var lines=new ArrayList<Object[]>();
        for(int index=0;index<salesCount;index++){
            long order=Ids.next();headers.add(new Object[]{order,context.tenant(),context.company(),"H-SO-"+index,context.customer(),day.minusDays(1+(index%360))});
            for(int line=0;line<10;line++)lines.add(new Object[]{Ids.next(),context.tenant(),context.company(),order,line+1,context.skus().get((index+line)%100),context.warehouse(),context.unit()});
        }
        jdbc.batchUpdate("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,status,price_tax_inclusive,goods_amount,total_amount,untaxed_amount) VALUES(?,?,?,?,?,?,'cancelled',1,100,100,100)",headers);
        jdbc.batchUpdate("INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,amount,line_net,receivable_net,tax_rate,untaxed_amount,tax_amount) VALUES(?,?,?,?,?,?,?,?,1,1,1,10,10,10,10,0,10,0)",lines);
        headers.clear();lines.clear();for(int index=0;index<purchaseCount;index++){
            long order=Ids.next();headers.add(new Object[]{order,context.tenant(),context.company(),"H-PO-"+index,context.customer(),day.minusDays(1+(index%360))});
            for(int line=0;line<10;line++)lines.add(new Object[]{Ids.next(),context.tenant(),context.company(),order,line+1,context.skus().get((index+line)%100),context.warehouse(),context.unit()});
        }
        jdbc.batchUpdate("INSERT INTO purchase_order(id,tenant_id,company_id,order_no,supplier_id,business_date,status,price_tax_inclusive,goods_amount,total_amount,untaxed_amount) VALUES(?,?,?,?,?,?,'cancelled',1,10,10,10)",headers);
        jdbc.batchUpdate("INSERT INTO purchase_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,receive_limit_base_qty,unit_price,amount,line_net,payable_net,tax_rate,untaxed_amount,tax_amount) VALUES(?,?,?,?,?,?,?,?,1,1,1,1,1,1,1,1,0,1,0)",lines);
        var hot=new ArrayList<Long>();headers.clear();lines.clear();for(int index=0;index<1000;index++) {
            long order=Ids.next(),line=Ids.next();hot.add(line);
            headers.add(new Object[]{order,context.tenant(),context.company(),"HOT-"+index,context.customer(),day});
            lines.add(new Object[]{line,context.tenant(),context.company(),order,1,context.skus().getFirst(),context.hotWarehouse(),context.unit()});
        }
        jdbc.batchUpdate("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,status,price_tax_inclusive,revision,approved_at,goods_amount,total_amount,untaxed_amount) VALUES(?,?,?,?,?,?,'approved',1,1,UTC_TIMESTAMP(3),10,10,10)",headers);
        jdbc.batchUpdate("INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,amount,line_net,receivable_net,tax_rate,untaxed_amount,tax_amount) VALUES(?,?,?,?,?,?,?,?,1,1,1,10,10,10,10,0,10,0)",lines);
        return List.copyOf(hot);
    }
    static String key(){return UUID.randomUUID().toString();}
    static long number(Map<String,Object> row,String key){return Long.parseLong(row.get(key).toString());}
}
