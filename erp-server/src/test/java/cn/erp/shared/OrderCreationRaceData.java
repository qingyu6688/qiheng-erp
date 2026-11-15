package cn.erp.shared;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 两家公司共享资料，模拟不同新订单同时向明细索引尾部插入。 */
@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
public abstract class OrderCreationRaceData {
    protected static final LocalDate DAY=LocalDate.of(2026,11,15);
    protected static final int LINE_COUNT=20;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    protected long tenant,partner,sku,unit;
    protected final long[] companies=new long[2],warehouses=new long[2];
    protected final CompanyAccess[] actors=new CompanyAccess[2];

    @BeforeEach void seedOrderRace() {
        tenant=Ids.next();partner=Ids.next();sku=Ids.next();unit=Ids.next();
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'新订单并发验收')",tenant,"RACE"+tenant);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'P','并发双角色往来')",partner,tenant);
        for(String role:List.of("customer","supplier"))jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,?)",Ids.next(),tenant,partner,role);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','并发分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','并发商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,'SKU','并发规格',0)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        for(int index=0;index<2;index++) {
            companies[index]=Ids.next();warehouses[index]=Ids.next();
            jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,'并发公司')",companies[index],tenant,"C"+index);
            jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','并发仓')",warehouses[index],tenant,companies[index]);
            actors[index]=new CompanyAccess(tenant,Ids.next(),companies[index],true,Set.of(),Set.of("sales_order:create","purchase_order:create","document:change_tax_rate"));
        }
    }

    @AfterEach void cleanupOrderRace() {
        for(String table:List.of("sales_order_fee","sales_order_line","sales_order","purchase_order_fee","purchase_order_line","purchase_order","warehouse","company","unit_conversion","sku","product","product_category","unit","partner_role","business_partner"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @FunctionalInterface
    protected interface WriteOrder {void write(int index,long id);}

    protected void raceNewOrders(String type,boolean fees,WriteOrder writer) throws Exception {
        if(!Set.of("sales_order","purchase_order").contains(type))throw new IllegalArgumentException("验收单据类型无效");
        String party=type.equals("sales_order")?"customer_id":"supplier_id";
        var headerReady=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<Future<Long>>();
            for(int index=0;index<2;index++) {
                int actor=index;
                jobs.add(pool.submit(()->new TransactionTemplate(manager).execute(status->{
                    long id=Ids.next();
                    jdbc.update("INSERT INTO "+type+"(id,tenant_id,company_id,order_no,"+party+",business_date,price_tax_inclusive) VALUES(?,?,?,?,?,?,1)",id,tenant,companies[actor],"RACE"+id,partner,DAY);
                    try {headerReady.await(10,TimeUnit.SECONDS);}
                    catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("新订单并发验收被中断",error);}
                    catch(BrokenBarrierException|TimeoutException error) {throw new IllegalStateException("两笔新表头未同时进入事务",error);}
                    writer.write(actor,id);
                    return id;
                })));
            }
            for(var job:jobs) {
                long id=job.get(30,TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+type+"_line WHERE order_id=? AND is_deleted=0",Integer.class,id)).isEqualTo(LINE_COUNT);
                assertThat(jdbc.queryForObject("SELECT MAX(line_no) FROM "+type+"_line WHERE order_id=?",Integer.class,id)).isEqualTo(LINE_COUNT);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+type+"_fee WHERE order_id=?",Integer.class,id)).isEqualTo(fees?1:0);
                assertThat(jdbc.queryForObject("SELECT total_amount FROM "+type+" WHERE id=?",BigDecimal.class,id)).isEqualByComparingTo(fees?"202":"200");
            }
        }
    }
    protected static String id(long value) {return Long.toString(value);}
}
