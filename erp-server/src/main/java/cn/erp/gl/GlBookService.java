package cn.erp.gl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;

@Service
@Transactional(readOnly = true)
public class GlBookService implements FinancialBookPort {
    private final GlSupport s;
    private final GlPresetSeeder seeder;
    private final GlDimensions dimensions;
    private final GlBalances balances;
    private final GlMappingResolver mappings;

    GlBookService(
            GlSupport s,
            GlPresetSeeder seeder,
            GlDimensions dimensions,
            GlBalances balances,
            GlMappingResolver mappings) {
        this.s = s;
        this.seeder = seeder;
        this.dimensions = dimensions;
        this.balances = balances;
        this.mappings = mappings;
    }

    public Info information(ActorScope a) {
        var row = s.bookOrNull(a);
        return row == null
                ? null
                : new Info(
                        number(row.get("id")),
                        row.get("standard").toString(),
                        row.get("status").toString(),
                        number(row.get("start_period_id")),
                        row.get("confirmed_hash") != null);
    }

    @Transactional
    public void lockControls(ActorScope actor) {
        s.book(actor, true);
        s.active(actor);
        s.lockPosting(actor);
    }

    public ControlBalance allowanceBase(ActorScope actor, long customer, LocalDate asOf) {
        var book = s.book(actor, false);
        var period = s.period(actor, number(book.get("start_period_id")), false);
        var opening =
                s.jdbc.queryForObject(
                        "SELECT COALESCE(SUM(-o.opening_signed),0) FROM gl_opening_balance o JOIN"
                            + " gl_subject x ON x.tenant_id=o.tenant_id AND"
                            + " x.company_id=o.company_id AND x.id=o.subject_id WHERE o.tenant_id=?"
                            + " AND o.company_id=? AND o.aux_customer_id=? AND x.code LIKE '1231%'",
                        java.math.BigDecimal.class, actor.tenantId(), actor.companyId(), customer);
        var manual =
                s.jdbc.queryForObject(
                        "SELECT COALESCE(SUM(IF(l.direction='C',l.amount,-l.amount)),0) FROM"
                            + " gl_voucher v JOIN gl_voucher_line l ON l.tenant_id=v.tenant_id AND"
                            + " l.company_id=v.company_id AND l.voucher_id=v.id JOIN gl_subject x"
                            + " ON x.tenant_id=l.tenant_id AND x.company_id=l.company_id AND"
                            + " x.id=l.subject_id WHERE v.tenant_id=? AND v.company_id=? AND"
                            + " v.source='manual' AND v.status IN('posted','reversed') AND"
                            + " v.is_deleted=0 AND l.aux_customer_id=? AND x.code LIKE '1231%' AND"
                            + " v.voucher_date<=?",
                        java.math.BigDecimal.class,
                        actor.tenantId(),
                        actor.companyId(),
                        customer,
                        asOf);
        return new ControlBalance(date(period.get("start_date")), opening.add(manual));
    }

    public Map<String, Object> get(CompanyAccess a) {
        s.require(a, "gl_config:view");
        var row = s.bookOrNull(a);
        return row == null ? Map.of("exists", false) : view(row);
    }

    public List<Map<String, Object>> presets(CompanyAccess a) {
        s.require(a, "gl_config:view");
        return s
                .jdbc
                .queryForList(
                        "SELECT standard,kind,code,name,payload,source_ref FROM gl_standard_preset"
                                + " ORDER BY standard,kind,code")
                .stream()
                .map(GlSupport::view)
                .toList();
    }

    @Transactional
    public Map<String, Object> create(CompanyAccess a, String key, GlModels.Book input) {
        s.require(a, "gl_config:edit");
        return s.execute(
                a,
                "gl_config.create",
                key,
                input,
                () -> {
                    if (!Set.of("enterprise", "small_enterprise").contains(input.standard()))
                        throw invalid("请选择企业准则或小企业准则");
                    var p = s.period(a, input.startPeriodId(), true);
                    if (!"open".equals(p.get("status"))) throw invalid("账套启用期间必须开放");
                    if (s.bookOrNull(a) != null)
                        throw new ApiException(409, "STATE_CONFLICT", "当前公司已经创建账套");
                    if (input.version() == null || input.version() != 0) throw invalid("新账套版本须为0");
                    long id = Ids.next();
                    s.jdbc.update(
                            "INSERT INTO"
                                + " gl_account_book(id,tenant_id,company_id,standard,report_basis,start_period_id,created_by)"
                                + " VALUES(?,?,?,?,'general_sample_2026',?,?)",
                            id,
                            a.tenantId(),
                            a.companyId(),
                            input.standard(),
                            input.startPeriodId(),
                            a.userId());
                    seeder.seed(a, input.standard());
                    s.audit.record(
                            a,
                            "gl_config.create",
                            "gl_account_book",
                            id,
                            null,
                            view(s.book(a, false)));
                    return view(s.book(a, false));
                });
    }

    public Map<String, Object> trial(CompanyAccess a) {
        s.require(a, "gl_opening:view");
        return trialInternal(a);
    }

    Map<String, Object> trialInternal(ActorScope a) {
        s.book(a, false);
        var totals =
                s.jdbc.queryForMap(
                        "SELECT COALESCE(SUM(GREATEST(opening_signed,0)),0)"
                                + " debit,COALESCE(SUM(GREATEST(-opening_signed,0)),0)"
                                + " credit,COALESCE(SUM(annual_opening_signed),0)"
                                + " annual_difference,COALESCE(SUM(year_debit-year_credit),0)"
                                + " year_difference FROM gl_opening_balance WHERE tenant_id=? AND"
                                + " company_id=?",
                        a.tenantId(),
                        a.companyId());
        var difference =
                GlBalances.decimal(totals, "debit").subtract(GlBalances.decimal(totals, "credit"));
        var result = view(totals);
        result.put("difference", difference.toPlainString());
        result.put(
                "balanced",
                difference.signum() == 0
                        && GlBalances.decimal(totals, "annual_difference").signum() == 0
                        && GlBalances.decimal(totals, "year_difference").signum() == 0);
        return result;
    }

    public List<Map<String, Object>> openings(CompanyAccess a) {
        s.require(a, "gl_opening:view");
        s.book(a, false);
        return s
                .jdbc
                .queryForList(
                        "SELECT o.*,s.code subject_code,s.name subject_name FROM gl_opening_balance"
                            + " o JOIN gl_subject s ON s.tenant_id=o.tenant_id AND"
                            + " s.company_id=o.company_id AND s.id=o.subject_id WHERE o.tenant_id=?"
                            + " AND o.company_id=? ORDER BY s.code,o.id",
                        a.tenantId(),
                        a.companyId())
                .stream()
                .map(GlSupport::view)
                .toList();
    }

    @Transactional
    public Map<String, Object> saveOpenings(CompanyAccess a, String key, GlModels.Openings input) {
        s.require(a, "gl_opening:edit");
        return s.execute(
                a,
                "gl_opening.save",
                key,
                input,
                () -> {
                    var book = s.book(a, true);
                    version(input.version(), book);
                    setup(book);
                    if (input.lines() == null || input.lines().size() > 2000)
                        throw invalid("期初最多2000行");
                    var keys = new HashSet<String>();
                    s.jdbc.update(
                            "DELETE FROM gl_opening_balance WHERE tenant_id=? AND company_id=?",
                            a.tenantId(),
                            a.companyId());
                    for (var line : input.lines()) {
                        if (line == null || line.subjectId() == null) throw invalid("期初科目不能为空");
                        var subject = dimensions.subject(a, line.subjectId(), false);
                        var aux = dimensions.validate(a, subject, line.aux());
                        if (!keys.add(GlDimensions.key(line.subjectId(), aux)))
                            throw invalid("相同科目及辅助维度的期初不能重复");
                        var debit = money(line.openingDebit(), false);
                        var credit = money(line.openingCredit(), false);
                        var annualD = money(line.annualOpeningDebit(), false);
                        var annualC = money(line.annualOpeningCredit(), false);
                        if (debit.signum() > 0 && credit.signum() > 0
                                || annualD.signum() > 0 && annualC.signum() > 0)
                            throw invalid("同一辅助明细期初只能有一个余额方向");
                        if ("income_expense".equals(subject.get("category"))
                                && debit.compareTo(credit) != 0)
                            throw invalid("损益科目期初余额须转入本年利润或利润分配，本年累计发生可保留");
                        var quantity = decimal(line.quantity(), 6, true);
                        if (quantity.signum() != 0
                                && Objects.toString(subject.get("quantity_unit"), "").isBlank())
                            throw invalid("科目未启用数量核算");
                        var args =
                                new ArrayList<Object>(
                                        List.of(
                                                Ids.next(),
                                                a.tenantId(),
                                                a.companyId(),
                                                line.subjectId()));
                        args.addAll(aux.values());
                        args.addAll(
                                List.of(
                                        debit.subtract(credit),
                                        money(line.yearDebit(), false),
                                        money(line.yearCredit(), false),
                                        annualD.subtract(annualC),
                                        quantity));
                        s.jdbc.update(
                                "INSERT INTO gl_opening_balance(id,tenant_id,company_id,subject_id,"
                                        + AUX_COLUMNS
                                        + ",opening_signed,year_debit,year_credit,annual_opening_signed,opening_quantity)"
                                        + " VALUES("
                                        + String.join(",", Collections.nCopies(args.size(), "?"))
                                        + ")",
                                args.toArray());
                    }
                    s.configurationChanged(a);
                    s.audit.record(
                            a,
                            "gl_opening.save",
                            "gl_account_book",
                            number(book.get("id")),
                            null,
                            input);
                    return Map.of("book", view(s.book(a, false)), "trial", trialInternal(a));
                });
    }

    public Map<String, Object> suggest(CompanyAccess a) {
        s.require(a, "gl_opening:edit");
        var book = s.book(a, false);
        setup(book);
        var p = s.period(a, number(book.get("start_period_id")), false);
        LocalDate start = date(p.get("start_date"));
        var config = mappings.mappings(a);
        var rows =
                s.jdbc.queryForList(
                        "SELECT l.*,j.source_type,j.source_id,j.business_date FROM journal j JOIN"
                            + " journal_line l ON l.tenant_id=j.tenant_id AND"
                            + " l.company_id=j.company_id AND l.journal_id=j.id WHERE j.tenant_id=?"
                            + " AND j.company_id=? AND j.business_date<? ORDER BY"
                            + " j.business_date,j.id,l.line_no",
                        a.tenantId(),
                        a.companyId(),
                        start);
        var result = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : rows) {
            var resolved = mappings.resolve(a, row, row, config).line();
            long subject = resolved.subjectId();
            var meta = dimensions.subject(a, subject, false);
            if ("income_expense".equals(meta.get("category"))) {
                String code =
                        start.getYear() == date(row.get("business_date")).getYear()
                                ? ("enterprise".equals(book.get("standard")) ? "4103" : "3103")
                                : ("enterprise".equals(book.get("standard")) ? "4104" : "3104");
                subject =
                        s.jdbc.queryForObject(
                                "SELECT id FROM gl_subject WHERE tenant_id=? AND company_id=? AND"
                                        + " code=?",
                                Long.class,
                                a.tenantId(),
                                a.companyId(),
                                code);
                resolved =
                        new GlModels.Line(
                                resolved.digest(),
                                subject,
                                resolved.direction(),
                                resolved.amount(),
                                "0",
                                null,
                                GlModels.Aux.empty(),
                                null);
            }
            String dimension = GlDimensions.key(subject, resolved.aux());
            var current = result.get(dimension);
            if (current == null) {
                current =
                        new LinkedHashMap<>(
                                Map.of(
                                        "subjectId",
                                        Long.toString(subject),
                                        "aux",
                                        resolved.aux(),
                                        "signed",
                                        BigDecimal.ZERO));
                result.put(dimension, current);
            }
            current.put(
                    "signed",
                    ((BigDecimal) current.get("signed"))
                            .add(
                                    GlDimensions.signed(
                                            resolved.direction(), money(resolved.amount(), true))));
        }
        return Map.of(
                "rows",
                result.values().stream()
                        .map(
                                r ->
                                        Map.of(
                                                "subjectId",
                                                r.get("subjectId"),
                                                "aux",
                                                auxView((GlModels.Aux) r.get("aux")),
                                                "openingDebit",
                                                ((BigDecimal) r.get("signed"))
                                                        .max(BigDecimal.ZERO)
                                                        .toPlainString(),
                                                "openingCredit",
                                                ((BigDecimal) r.get("signed"))
                                                        .negate()
                                                        .max(BigDecimal.ZERO)
                                                        .toPlainString()))
                        .toList(),
                "notice",
                "建议值来自启用前运营分录，请补齐年初及本年累计，核对科目、辅助和财务调整后保存；本接口不自动写入");
    }

    @Transactional
    public Map<String, Object> confirm(CompanyAccess a, String key, GlModels.Confirmation input) {
        s.require(a, "gl_config:confirm");
        return s.execute(
                a,
                "gl_config.confirm",
                key,
                input,
                () -> {
                    var book = s.book(a, true);
                    version(input.version(), book);
                    String statement = text(input.statement(), 500, "财务确认说明");
                    if (s.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM gl_voucher_word WHERE tenant_id=? AND"
                                            + " company_id=? AND is_default=1 AND status='active'",
                                    Long.class,
                                    a.tenantId(),
                                    a.companyId())
                            != 1) throw invalid("请配置唯一的有效默认凭证字");
                    String hash = hash(a);
                    s.jdbc.update(
                            "UPDATE gl_account_book SET"
                                + " confirmed_hash=?,confirmed_by=?,confirmed_at=UTC_TIMESTAMP(3),version=version+1"
                                + " WHERE tenant_id=? AND company_id=?",
                            hash,
                            a.userId(),
                            a.tenantId(),
                            a.companyId());
                    s.audit.record(
                            a,
                            "gl_config.confirm",
                            "gl_account_book",
                            number(book.get("id")),
                            null,
                            Map.of("hash", hash, "statement", statement));
                    return view(s.book(a, false));
                });
    }

    @Transactional
    public Map<String, Object> activate(
            CompanyAccess a, String action, String key, GlModels.Version input) {
        s.require(a, "gl_opening:activate");
        if (!Set.of("activate", "deactivate").contains(action)) throw invalid("账套操作无效");
        return s.execute(
                a,
                "gl_opening." + action,
                key,
                input,
                () -> {
                    var snapshot = s.book(a, false);
                    var openingPeriod = s.period(a, number(snapshot.get("start_period_id")), false);
                    // 先锁期间再锁账套，防止未启用公司关账与启用并发提交。
                    s.openPeriod(a, date(openingPeriod.get("start_date")));
                    var book = s.book(a, true);
                    version(input.version(), book);
                    s.lockPosting(a);
                    long period = number(book.get("start_period_id"));
                    var p = s.period(a, period, false);
                    if (action.equals("activate")) {
                        setup(book);
                        if (!"open".equals(p.get("status"))) throw invalid("启用期间已经关闭");
                        if (book.get("confirmed_hash") == null
                                || !book.get("confirmed_hash").equals(hash(a)))
                            throw invalid("请先由财务核对当前科目、辅助、映射及报表配置并确认");
                        if (!Boolean.TRUE.equals(trialInternal(a).get("balanced")))
                            throw invalid("期初、年初及本年累计借贷试算未平衡");
                        if (s.jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM accounting_period WHERE tenant_id=?"
                                                + " AND company_id=? AND start_date<? AND"
                                                + " status<>'closed'",
                                        Long.class,
                                        a.tenantId(),
                                        a.companyId(),
                                        p.get("start_date"))
                                > 0) throw invalid("启用前的业务期间须先关闭");
                        s.jdbc.update(
                                "UPDATE gl_account_book SET"
                                    + " status='active',opening_mapping_json=?,activated_at=UTC_TIMESTAMP(3),version=version+1"
                                    + " WHERE tenant_id=? AND company_id=?",
                                s.json.writeValueAsString(mappings.mappings(a)),
                                a.tenantId(),
                                a.companyId());
                        balances.refresh(a, period);
                    } else {
                        if (!"active".equals(book.get("status"))) throw invalid("账套尚未启用");
                        if (s.jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM gl_voucher WHERE tenant_id=? AND"
                                            + " company_id=? AND status IN('posted','reversed')",
                                        Long.class,
                                        a.tenantId(),
                                        a.companyId())
                                > 0) throw invalid("账套已有记账事实，不能反启用");
                        s.jdbc.update(
                                "DELETE FROM gl_balance WHERE tenant_id=? AND company_id=?",
                                a.tenantId(),
                                a.companyId());
                        s.jdbc.update(
                                "UPDATE gl_account_book SET status='setup',version=version+1 WHERE"
                                        + " tenant_id=? AND company_id=?",
                                a.tenantId(),
                                a.companyId());
                    }
                    var after = view(s.book(a, false));
                    s.audit.record(
                            a,
                            "gl_opening." + action,
                            "gl_account_book",
                            number(book.get("id")),
                            book,
                            after);
                    return after;
                });
    }

    String hash(ActorScope a) {
        var config = new LinkedHashMap<String, Object>();
        for (String table :
                List.of(
                        "gl_subject",
                        "gl_aux_type",
                        "gl_aux_item",
                        "gl_cash_flow_item",
                        "gl_business_mapping",
                        "gl_voucher_word",
                        "gl_digest",
                        "gl_voucher_template",
                        "gl_opening_balance",
                        "gl_closing_scheme",
                        "gl_report_template",
                        "gl_export_format"))
            config.put(
                    table,
                    s
                            .jdbc
                            .queryForList(
                                    "SELECT * FROM "
                                            + table
                                            + " WHERE tenant_id=? AND company_id=? ORDER BY id",
                                    a.tenantId(),
                                    a.companyId())
                            .stream()
                            .map(GlSupport::view)
                            .toList());
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            s.json.writeValueAsString(config)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少摘要算法", e);
        }
    }

    private static void setup(Map<String, Object> book) {
        if (!"setup".equals(book.get("status"))) throw invalid("已启用账套不能修改期初");
    }
}
