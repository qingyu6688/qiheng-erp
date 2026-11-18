package cn.erp.gl;

import cn.erp.identity.CompanyAccess;
import cn.erp.jobs.OutboxDelivery;
import cn.erp.jobs.OutboxService;
import cn.erp.purchasefinance.PaymentService;
import cn.erp.purchasefinance.TreasuryModels;
import cn.erp.salesfinance.FinanceModels;
import cn.erp.salesfinance.ReceiptService;
import cn.erp.shared.Ids;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** D 盘专用历史构造。每张真实单据和进度在同一事务提交，允许跨进程继续。 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="ERP_O30_STAGE", matches="smoke|first|remaining")
class GlHistoryCapacityIT {
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    @Autowired GlBookService books;
    @Autowired ReceiptService receipts;
    @Autowired PaymentService payments;
    @Autowired OutboxService events;
    @Autowired OutboxDelivery delivery;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired GlReconciliation reconciliation;
    private long remainingBudget = Long.MAX_VALUE;
    private long generated;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");
    record Actors(long tenant, long company, long maker, long reviewer, long partner, long bank, Set<String> permissions) {
        CompanyAccess actor(long user) { return new CompanyAccess(tenant,user,company,true,Set.of(),permissions); }
    }
    record Tally(long id, String period, long confirmedReceipts, long confirmedPayments, long journals,
                 long deliveredLedger, long deliveredGl, long posted, long lines, long balances,
                 String debit, String credit, long mismatchedLines) {}

    static String env(String name) { return Objects.requireNonNull(System.getenv(name), "缺少配置 " + name); }

    static Path real(String input) throws java.io.IOException {
        Path path=Path.of(input);
        if(!path.isAbsolute()) throw new IllegalArgumentException("隔离路径必须是绝对路径");
        Path resolved=path.toRealPath();
        if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                && (!resolved.getRoot().toString().matches("(?i)[A-Z]:.*") || resolved.getRoot().toString().toUpperCase(Locale.ROOT).startsWith("C:")))
            throw new IllegalArgumentException("Windows 测试必须使用非 C 盘的本机目录");
        return resolved;
    }

    static Path inside(String name) throws java.io.IOException {
        Path root=real(env("ERP_O30_ISOLATION_ROOT")), child=real(env(name));
        if(!child.startsWith(root)) throw new IllegalArgumentException("指定路径不属于本轮隔离根目录：" + name);
        return child;
    }

    @DynamicPropertySource
    static void requireIsolatedEnvironment(DynamicPropertyRegistry registry) throws Exception {
        String database=env("TEST_DB_NAME");
        int port=Integer.parseInt(env("DB_PORT"));
        if(!List.of("localhost","127.0.0.1").contains(env("DB_HOST")) || !database.matches("erp_load_o30_[a-z0-9]+") || port<1 || port>65535)
            throw new IllegalArgumentException("历史生成须使用回环地址和专用数据库");
        Path expected=inside("ERP_O30_DATADIR");
        inside("ERP_TEST_ROOT");
        String url="jdbc:mysql://127.0.0.1:"+port+"/"+database+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED&allowPublicKeyRetrieval=true";
        try(var connection=java.sql.DriverManager.getConnection(url,env("TEST_DB_USER"),env("TEST_DB_PASSWORD"));
                var stmt=connection.createStatement();var rows=stmt.executeQuery("SELECT DATABASE(),@@datadir")){
            if(!rows.next() || !database.equals(rows.getString(1)) || !expected.equals(real(rows.getString(2))))
                throw new IllegalArgumentException("MySQL 连接的数据库或实例数据目录与本轮配置不符");
        }
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.flyway.url",()->url);
    }

    @Test
    void generate() throws Exception {
        String run = env("ERP_O30_RUN");
        assertThat(run).matches("[a-f0-9]{12}");
        assertThat(real(db.queryForObject("SELECT @@datadir",String.class))).isEqualTo(inside("ERP_O30_DATADIR"));
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(env("TEST_DB_NAME"));
        String stage = env("ERP_O30_STAGE");
        int target = stage.equals("smoke") ? Integer.parseInt(System.getenv().getOrDefault("ERP_O30_SMOKE_ROWS","2")) : 100000;
        assertThat(target).isBetween(1,100000);
        remainingBudget=Long.parseLong(System.getenv().getOrDefault("ERP_O30_MAX_NEW",String.valueOf(Long.MAX_VALUE)));
        assertThat(remainingBudget).isPositive();
        YearMonth first = YearMonth.parse(env("ERP_O30_START"));
        assertThat(first.plusMonths(11).atDay(Math.min(target,28))).isBeforeOrEqualTo(LocalDate.now());
        String tenantCode = "LOAD-" + run + "-0";
        long started = System.nanoTime();
        cursorTable();
        Actors a = prepare(tenantCode);
        if (!stage.equals("smoke")) pinIdentity(a);
        if (stage.equals("remaining")) verifyFirstEvidence(a, first, tenantCode, target);
        if (stage.equals("first")) assertThat(count("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND company_id=? AND business_date>=?",
                a.tenant,a.company,first.plusMonths(1).atDay(1))).as("首期基线前不得存在后期业务事实").isZero();
        var results = new ArrayList<Tally>();
        int firstIndex = stage.equals("remaining") ? 1 : 0;
        int lastIndex = stage.equals("first") ? 0 : 11;
        boolean complete=false;
        long generatingStarted=System.nanoTime();
        for (int index=firstIndex; index<=lastIndex; index++) {
            YearMonth month = first.plusMonths(index);
            long period = ensurePeriod(a,month);
            if (index == 0) activateBook(a,period);
            generateMonth(a,month,period,target);
            int actual=Math.toIntExact(count("SELECT completed FROM o30_history_cursor WHERE tenant_id=? AND company_id=? AND period_id=?",a.tenant,a.company,period));
            assertThat(actual).isLessThanOrEqualTo(target);
            results.add(tally(a,month,period,actual));
            complete=index==lastIndex && actual==target;
            if (remainingBudget==0) break;
        }
        double businessSeconds=(System.nanoTime()-generatingStarted)/1e9;
        if(stage.equals("smoke")){
            assertThat(reconciliation.check(a.actor(a.reviewer),"gl_trial")).as("总账余额逐行比对").isEmpty();
            assertThat(reconciliation.check(a.actor(a.reviewer),"gl_operational")).as("业务分录逐行比对").isEmpty();
        }
        var sampleList=new ArrayList<Map<String,Object>>();
        if (complete) sampleList.addAll(prepareManual(a,first.plusMonths(lastIndex),results.getLast().id(),stage.equals("smoke")?2:100));
        Path output=Path.of(env("ERP_O30_RESULT"));
        if (!output.isAbsolute() || !real(output.getParent().toString()).startsWith(inside("ERP_TEST_ROOT"))) {
            throw new IllegalArgumentException("历史证据必须在本轮 D 盘测试根目录内");
        }
        var evidence = new LinkedHashMap<String,Object>(Map.of("tenantCode", tenantCode,"tenantId",String.valueOf(a.tenant),
                "companyId",String.valueOf(a.company),"stage",stage,"targetPerPeriod",target,"periods",results,
                "elapsedSeconds",(System.nanoTime()-started)/1e9));
        evidence.put("postVouchers",sampleList);
        evidence.put("generated",generated);
        evidence.put("generationSeconds",businessSeconds);
        evidence.put("complete",complete);
        if(stage.equals("smoke")) evidence.put("reconciliation",Map.of("gl_trial",0,"gl_operational",0));
        Files.writeString(output,json.writeValueAsString(evidence),java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    void cursorTable() {
        db.execute("""
            CREATE TABLE IF NOT EXISTS o30_history_cursor (
             tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL,
             period_id BIGINT UNSIGNED NOT NULL, completed INT NOT NULL,
             PRIMARY KEY(tenant_id,company_id,period_id)
            ) ENGINE=InnoDB
            """);
    }

    void pinIdentity(Actors a) {
        String dataset=env("ERP_O30_DATASET"), source=env("ERP_O30_SOURCE"), artifact=env("ERP_O30_ARTIFACT"), runtime=env("ERP_O30_ENVIRONMENT");
        assertThat(dataset).matches("[a-z0-9_-]{1,64}");
        assertThat(source).matches("[a-f0-9]{40}");
        assertThat(artifact).matches("[a-f0-9]{64}");
        assertThat(runtime).matches("[a-f0-9]{64}");
        String database=db.queryForObject("""
            SELECT CONCAT_WS('|',VERSION(),@@innodb_buffer_pool_size,@@innodb_flush_log_at_trx_commit,@@sync_binlog,
                @@max_connections,(SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1))
            """,String.class);
        String fingerprint=String.join("|",dataset,source,artifact,runtime,database,env("ERP_O30_START"));
        db.execute("CREATE TABLE IF NOT EXISTS o30_history_run(tenant_id BIGINT UNSIGNED,company_id BIGINT UNSIGNED,identity_value VARCHAR(512) NOT NULL,PRIMARY KEY(tenant_id,company_id)) ENGINE=InnoDB");
        db.update("INSERT INTO o30_history_run VALUES(?,?,?) ON DUPLICATE KEY UPDATE identity_value=identity_value",a.tenant,a.company,fingerprint);
        assertThat(db.queryForObject("SELECT identity_value FROM o30_history_run WHERE tenant_id=? AND company_id=?",String.class,a.tenant,a.company))
                .as("两阶段须使用相同数据集、构建标识、运行环境和数据库配置").isEqualTo(fingerprint);
    }

    Actors prepare(String tenantCode) {
        tx.executeWithoutResult(status -> {
            if (count("SELECT COUNT(*) FROM tenant WHERE code=?",tenantCode)>0) return;
            long tenant=Ids.next(),company=Ids.next(),role=Ids.next(),partner=Ids.next(),bank=Ids.next();
            db.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'历史容量企业')",tenant,tenantCode);
            db.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'C0','历史容量公司')",company,tenant);
            db.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",Ids.next(),tenant,company);
            db.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'LOAD','容量验收')",role,tenant);
            db.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'O30','容量往来单位')",partner,tenant);
            for(String type : List.of("customer","supplier"))
                db.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,?)",Ids.next(),tenant,partner,type);
            db.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,'O30','容量银行','bank')",bank,tenant,company);
            db.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,bank);
            String hash=passwords.encode(env("ERP_O30_LOGIN_PASSWORD"));
            for(String user : List.of("operator","reviewer")){
                long id=Ids.next();
                db.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,'容量财务人员',?)",id,tenant,user,hash);
                db.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,id,role);
                db.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,id,company);
            }
            for(long permission:db.queryForList("SELECT id FROM sys_permission",Long.class))
                db.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,role,permission);
        });
        long tenant=count("SELECT id FROM tenant WHERE code=?",tenantCode);
        long company=count("SELECT id FROM company WHERE tenant_id=? AND code='C0'",tenant);
        return new Actors(tenant,company,count("SELECT id FROM sys_user WHERE tenant_id=? AND username='operator'",tenant),
                count("SELECT id FROM sys_user WHERE tenant_id=? AND username='reviewer'",tenant),
                count("SELECT id FROM business_partner WHERE tenant_id=? AND code='O30'",tenant),
                count("SELECT id FROM cash_account WHERE tenant_id=? AND company_id=? AND code='O30'",tenant,company),
                Set.copyOf(db.queryForList("SELECT code FROM sys_permission",String.class)));
    }

    long ensurePeriod(Actors a, YearMonth month) {
        String label=month.format(PERIOD);
        return tx.execute(status -> {
            if (count("SELECT COUNT(*) FROM accounting_period WHERE tenant_id=? AND company_id=? AND period=?",a.tenant,a.company,label)==0)
                db.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",Ids.next(),a.tenant,a.company,label,month.atDay(1),month.atEndOfMonth());
            return count("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND period=? AND status='open'",a.tenant,a.company,label);
        });
    }

    void activateBook(Actors a,long firstPeriod) {
        tx.executeWithoutResult(status -> {
            if(count("SELECT COUNT(*) FROM gl_account_book WHERE tenant_id=? AND company_id=? AND start_period_id=? AND status='active'",a.tenant,a.company,firstPeriod)>0) return;
            var draft=books.create(a.actor(a.maker),"o30-book",new GlModels.Book("enterprise",firstPeriod,null,0L));
            var signed=books.confirm(a.actor(a.reviewer),"o30-book-confirm",new GlModels.Confirmation(GlSupport.number(draft.get("version")),"容量样本账套确认"));
            books.activate(a.actor(a.maker),"activate","o30-book-active",new GlModels.Version(GlSupport.number(signed.get("version")),null));
        });
    }

    void generateMonth(Actors a, YearMonth month, long period, int target) throws InterruptedException {
        db.update("INSERT INTO o30_history_cursor VALUES(?,?,?,0) ON DUPLICATE KEY UPDATE completed=completed",a.tenant,a.company,period);
        drain(a);
        int count=0;
        while(remainingBudget>0){
            int now=tx.execute(status -> {
                int previous=Math.toIntExact(count("SELECT completed FROM o30_history_cursor WHERE tenant_id=? AND company_id=? AND period_id=? FOR UPDATE",a.tenant,a.company,period));
                if(previous>=target) return -1;
                int seq=previous+1;
                String key="o30-"+month.format(PERIOD)+"-"+seq;
                LocalDate date=month.atDay(1+(seq-1)%Math.min(month.lengthOfMonth(),28));
                if (seq%2==1) {
                    var row=receipts.create(a.actor(a.maker),key+"c",new FinanceModels.Receipt("customer",a.partner,null,a.bank,"2.00",date,null,null,key,null));
                    long id=GlSupport.number(row.get("id"));
                    var submitted=receipts.command(a.actor(a.maker),id,"submit",key+"s",new FinanceModels.Version(GlSupport.number(row.get("version")),null,null));
                    receipts.command(a.actor(a.reviewer),id,"confirm",key+"f",new FinanceModels.Version(GlSupport.number(submitted.get("version")),null,null));
                } else {
                    var row=payments.create(a.actor(a.maker),key+"c",new TreasuryModels.Payment("supplier",a.partner,null,a.bank,"1.00",date,null,null,key,null));
                    long id=GlSupport.number(row.get("id"));
                    var submitted=payments.command(a.actor(a.maker),id,"submit",key+"s",new TreasuryModels.Version(GlSupport.number(row.get("version")),null));
                    payments.command(a.actor(a.reviewer),id,"confirm",key+"f",new TreasuryModels.Version(GlSupport.number(submitted.get("version")),null));
                }
                db.update("UPDATE o30_history_cursor SET completed=? WHERE tenant_id=? AND company_id=? AND period_id=?",seq,a.tenant,a.company,period);
                return seq;
            });
            if(now<0) break;
            generated++;
            remainingBudget--;
            if (++count%25==0) drain(a);
        }
        drain(a);
    }

    void drain(Actors a) throws InterruptedException {
        long stop=System.nanoTime()+120_000_000_000L;
        while(true){
            var work=events.claim(20,a.tenant);
            if(work.isEmpty()){
                long pending=count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND status IN('pending','processing','dead')",a.tenant);
                if(pending==0) return;
                if(count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND status='dead'",a.tenant)>0 || System.nanoTime()>=stop)
                    throw new IllegalStateException("存在死信或未按时完成的事件，请检查本轮 Outbox");
                Thread.sleep(250);
            }
            for(var event:work)
                try { assertThat(delivery.deliver(event)).as("消费租约有效").isTrue(); }
                catch(RuntimeException failure){ delivery.failed(event); throw failure; }
        }
    }

    long count(String sql,Object... values){return db.queryForObject(sql,Long.class,values);}

    Tally tally(Actors a, YearMonth month, long period,int target) {
        String base=" FROM journal j WHERE j.tenant_id=? AND j.company_id=? AND j.period_id=? AND j.source_type IN('receipt','payment')";
        Object[] args={a.tenant,a.company,period};
        long ledger=count("SELECT COUNT(*)"+base,args);
        long receipts=count("SELECT COUNT(*)"+base+" AND j.source_type='receipt' AND EXISTS (SELECT 1 FROM receipt r WHERE r.id=j.source_id AND r.tenant_id=j.tenant_id AND r.company_id=j.company_id AND r.status='confirmed' AND r.created_by<>r.confirmed_by)",args);
        long payments=count("SELECT COUNT(*)"+base+" AND j.source_type='payment' AND EXISTS (SELECT 1 FROM payment p WHERE p.id=j.source_id AND p.tenant_id=j.tenant_id AND p.company_id=j.company_id AND p.status='confirmed' AND p.created_by<>p.confirmed_by)",args);
        String event=" AND EXISTS(SELECT 1 FROM outbox_event e JOIN inbox_event i ON i.tenant_id=e.tenant_id AND i.event_id=e.id WHERE e.tenant_id=j.tenant_id AND e.aggregate_id=j.id AND e.status='done' ";
        long ledgerDone=count("SELECT COUNT(*)"+base+event+" AND e.aggregate_type='journal' AND e.event_type='journal.posted' AND i.consumer='ledger_projection')",args);
        long glDone=count("SELECT COUNT(*)"+base+event+" AND e.aggregate_type='gl_journal' AND e.event_type='gl.journal.requested' AND i.consumer='gl_voucher')",args);
        long vouchers=count("SELECT COUNT(*)"+base+" AND EXISTS(SELECT 1 FROM gl_voucher v WHERE v.tenant_id=j.tenant_id AND v.company_id=j.company_id AND v.journal_id=j.id AND v.period_id=j.period_id AND v.source='business' AND v.status='posted')",args);
        long lines=count("""
            SELECT COUNT(*) FROM gl_voucher_line l JOIN gl_voucher v ON v.tenant_id=l.tenant_id AND v.company_id=l.company_id AND v.id=l.voucher_id
            JOIN journal_line jl ON jl.id=l.journal_line_id AND jl.tenant_id=l.tenant_id AND jl.company_id=l.company_id AND jl.journal_id=v.journal_id
            WHERE v.tenant_id=? AND v.company_id=? AND v.period_id=? AND v.status='posted' AND v.source='business'
            """,args);
        long balances=count("SELECT COUNT(*) FROM gl_balance WHERE tenant_id=? AND company_id=? AND period_id=?",args);
        assertThat(receipts).isEqualTo((target+1)/2);
        assertThat(payments).isEqualTo(target/2);
        assertThat(List.of(ledger,ledgerDone,glDone,vouchers)).containsOnly((long)target);
        assertThat(lines).isEqualTo(2L*target);
        assertThat(balances).isPositive();
        var totals=db.queryForMap("""
            SELECT COALESCE(SUM(IF(l.direction='D',l.amount,0)),0) debit,COALESCE(SUM(IF(l.direction='C',l.amount,0)),0) credit,
              COALESCE(SUM(l.direction<>jl.direction OR l.amount<>jl.amount
                   OR jl.amount<>IF(j.source_type='receipt',2.00,1.00)),0) differences
            FROM gl_voucher_line l JOIN gl_voucher v ON v.id=l.voucher_id AND v.tenant_id=l.tenant_id AND v.company_id=l.company_id
              JOIN journal j ON j.id=v.journal_id AND j.tenant_id=v.tenant_id AND j.company_id=v.company_id AND j.period_id=v.period_id
              JOIN journal_line jl ON jl.id=l.journal_line_id AND jl.journal_id=j.id AND jl.tenant_id=j.tenant_id AND jl.company_id=j.company_id
            WHERE v.tenant_id=? AND v.company_id=? AND v.period_id=? AND v.status='posted' AND v.source='business'
            """,args);
        var debit=(java.math.BigDecimal)totals.get("debit");
        var credit=(java.math.BigDecimal)totals.get("credit");
        var expected=java.math.BigDecimal.valueOf(2*receipts+payments);
        assertThat(debit).isEqualByComparingTo(expected);
        assertThat(credit).isEqualByComparingTo(expected);
        long errors=((Number)totals.get("differences")).longValue();
        assertThat(errors).isZero();
        return new Tally(period,month.format(PERIOD),receipts,payments,ledger,ledgerDone,glDone,vouchers,lines,balances,
                debit.toPlainString(),credit.toPlainString(),errors);
    }

    @Autowired GlVoucherService manual;
    List<Map<String,Object>> prepareManual(Actors a,YearMonth month,long period,int howMany) {
        db.execute("CREATE TABLE IF NOT EXISTS o30_history_manual(tenant_id BIGINT UNSIGNED,company_id BIGINT UNSIGNED,period_id BIGINT UNSIGNED,seq INT,voucher_id BIGINT UNSIGNED,approved_version BIGINT UNSIGNED,PRIMARY KEY(tenant_id,company_id,period_id,seq),UNIQUE KEY(voucher_id)) ENGINE=InnoDB");
        long debit=count("SELECT id FROM gl_subject WHERE tenant_id=? AND company_id=? AND code='6602'",a.tenant,a.company);
        long credit=count("SELECT id FROM gl_subject WHERE tenant_id=? AND company_id=? AND code='2241'",a.tenant,a.company);
        long word=count("SELECT id FROM gl_voucher_word WHERE tenant_id=? AND company_id=? AND is_default=1",a.tenant,a.company);
        for (int i=0;i<howMany;i++) {
            final int seq=i+1;
            tx.executeWithoutResult(status -> {
                if(count("SELECT COUNT(*) FROM o30_history_manual WHERE tenant_id=? AND company_id=? AND period_id=? AND seq=?",a.tenant,a.company,period,seq)>0) return;
                String key="o30-manual-"+month.format(PERIOD)+"-"+seq;
                var lines=List.of(new GlModels.Line("容量记账样本",debit,"D","100.00","0",null,GlModels.Aux.empty(),null),
                        new GlModels.Line("容量记账样本",credit,"C","100.00","0",null,GlModels.Aux.empty(),null));
                var row=manual.save(a.actor(a.maker),null,key+"c",new GlModels.Voucher(word,month.atDay(1),0,lines,null,null));
                long id=GlSupport.number(row.get("id"));
                var submitted=manual.change(a.actor(a.maker),id,"submit",key+"s",new GlModels.Version(GlSupport.number(row.get("version")),null));
                var approved=manual.change(a.actor(a.reviewer),id,"approve",key+"a",new GlModels.Version(GlSupport.number(submitted.get("version")),null));
                db.update("INSERT INTO o30_history_manual VALUES(?,?,?,?,?,?)",a.tenant,a.company,period,seq,id,GlSupport.number(approved.get("version")));
            });
        }
        return db.query("SELECT voucher_id,approved_version FROM o30_history_manual WHERE tenant_id=? AND company_id=? AND period_id=? ORDER BY seq",
                (rs,n)->Map.of("id",rs.getString(1),"version",rs.getLong(2)),a.tenant,a.company,period);
    }

    void verifyFirstEvidence(Actors a, YearMonth month,String tenantCode,int target) throws Exception {
        Path proof=inside("ERP_O30_FIRST_EVIDENCE");
        var root=json.readTree(Files.readString(proof));
        var manifest=root.path("manifest");
        assertThat(root.path("kind").asString()).isEqualTo("posting");
        assertThat(manifest.path("phase").asString()).isEqualTo("month01");
        for (var entry:Map.of("dataset","ERP_O30_DATASET","sourceCommit","ERP_O30_SOURCE","artifactSha256","ERP_O30_ARTIFACT","environmentSha256","ERP_O30_ENVIRONMENT").entrySet())
            assertThat(manifest.path(entry.getKey()).asString()).isEqualTo(env(entry.getValue()));
        assertThat(manifest.path("tenantCode").asString()).isEqualTo(tenantCode);
        assertThat(manifest.path("companyId").asString()).isEqualTo(String.valueOf(a.company));
        assertThat(manifest.path("tenantId").asString()).isEqualTo(String.valueOf(a.tenant));
        assertThat(manifest.path("periods").size()).isOne();
        assertThat(manifest.path("periods").get(0).path("period").asString()).isEqualTo(month.format(PERIOD));
        long firstId=count("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND period=?",a.tenant,a.company,month.format(PERIOD));
        assertThat(manifest.path("periods").get(0).path("id").asString()).isEqualTo(String.valueOf(firstId));
        String dbFingerprint=db.queryForObject("""
            SELECT CONCAT_WS('|',VERSION(),@@innodb_buffer_pool_size,@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@max_connections,
              (SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1))
            """,String.class);
        for(String side:List.of("before","after")){
            var snapshot=root.path(side);
            var identity=snapshot.path("identity");
            assertThat(identity.path("tenantCode").asString()).isEqualTo(tenantCode);
            assertThat(identity.path("companyId").asString()).isEqualTo(String.valueOf(a.company));
            assertThat(identity.path("bookStatus").asString()).isEqualTo("active");
            var fields=List.of("databaseVersion","bufferPoolBytes","flushLogAtCommit","syncBinlog","maxConnections","schemaVersion");
            assertThat(String.join("|",fields.stream().map(k->identity.path(k).asString()).toList())).isEqualTo(dbFingerprint);
            int matched=0;
            for(var row:snapshot.path("periods")){
                if(!row.path("id").asString().equals(String.valueOf(firstId))){
                    assertThat(row.path("allPosted").asLong()).as("首期证据不允许含后续期间过账").isZero();
                    continue;
                }
                matched++;
                assertThat(row.path("businessPosted").asLong()).isGreaterThanOrEqualTo(target);
                assertThat(row.path("linkedBusinessPosted").asLong()).isGreaterThanOrEqualTo(target);
                assertThat(row.path("voucherLines").asLong()).isGreaterThanOrEqualTo(target*2L);
                assertThat(row.path("balanceRows").asLong()).isPositive();
            }
            assertThat(matched).as("首期前后快照各含唯一一期").isOne();
        }
        var samples=root.path("samples");
        assertThat(samples.size()).isGreaterThanOrEqualTo(100);
        Set<String> ids=new HashSet<>();
        for(var sample:samples){
            assertThat(sample.path("posted").asBoolean()).isTrue();
            assertThat(sample.path("httpStatus").asInt()).isEqualTo(200);
            assertThat(sample.path("periodId").asString()).isEqualTo(String.valueOf(firstId));
            String voucher=sample.path("voucherId").asString();
            assertThat(ids.add(voucher)).isTrue();
            assertThat(sample.path("elapsedMs").asDouble()).isPositive();
            assertThat(sample.path("afterVersion").asLong()).isEqualTo(sample.path("version").asLong()+1);
            assertThat(count("""
                SELECT COUNT(*) FROM o30_history_manual m JOIN gl_voucher v ON v.id=m.voucher_id AND v.tenant_id=m.tenant_id AND v.company_id=m.company_id AND v.period_id=m.period_id
                WHERE m.tenant_id=? AND m.company_id=? AND m.period_id=? AND m.voucher_id=? AND m.approved_version=? AND v.source='manual' AND v.status='posted'
                """,a.tenant,a.company,firstId,voucher,sample.path("version").asLong())).isOne();
        }
        tally(a,month,firstId,target);
    }
}
