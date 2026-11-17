package cn.erp.crm;

import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.crm.CrmCycleData.*;

@Service
@Transactional(readOnly = true)
class CrmOpportunityService {
    private final JdbcTemplate jdbc;
    private final CrmScope scope;
    private final CatalogAvailability catalog;
    private final AuditService audit;

    CrmOpportunityService(JdbcTemplate jdbc, CrmScope scope, CatalogAvailability catalog, AuditService audit) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.catalog = catalog;
        this.audit = audit;
    }

    List<Map<String, Object>> groups(CompanyAccess actor) {
        return jdbc.queryForList("""
                SELECT * FROM crm_business_stage_group WHERE tenant_id=? AND company_id=? ORDER BY name,id
                """, actor.tenantId(), actor.companyId()).stream().map(group -> groupView(actor, group))
                .filter(group -> groupVisible(actor, group)).toList();
    }

    private boolean groupVisible(CompanyAccess actor, Map<String, Object> group) {
        if (actor.orgScope().allDepartments()) return true;
        var departments = (List<?>) group.get("departmentIds");
        return departments.isEmpty() || departments.stream().anyMatch(department ->
                actor.orgScope().allows(Long.parseLong(department.toString()), actor.userId()));
    }

    @Transactional
    public Map<String, Object> createGroup(CompanyAccess actor, CrmCycleModels.StageGroup input) {
        if (!actor.orgScope().allDepartments()) throw forbidden("设置商机阶段需要公司级部门范围");
        String name = required(input.name(), 80, "阶段组名称");
        long group = Ids.next();
        var departments = input.departmentIds() == null ? Set.<String>of() : new HashSet<>(input.departmentIds());
        for (String department : departments) validateDepartment(actor, id(department));
        jdbc.update("INSERT INTO crm_business_stage_group(id,tenant_id,company_id,name) VALUES(?,?,?,?)",
                group, actor.tenantId(), actor.companyId(), name);
        for (String department : departments) jdbc.update("INSERT INTO crm_stage_department VALUES(?,?,?,?)",
                actor.tenantId(), actor.companyId(), group, id(department));
        var names = new HashSet<String>();
        var positions = new HashSet<Integer>();
        int index = 0;
        for (var stage : input.stages()) {
            String stageName = required(stage.name(), 80, "阶段名称");
            if (!names.add(stageName)) throw invalid("同一阶段组内的阶段名称不能重复");
            ++index;
            int order = stage.sort() == null ? index : stage.sort();
            if (!positions.add(order)) throw invalid("同一阶段组内的阶段顺序不能重复");
            jdbc.update("INSERT INTO crm_business_stage(id,tenant_id,company_id,group_id,name,win_rate,sort) VALUES(?,?,?,?,?,?,?)",
                    Ids.next(), actor.tenantId(), actor.companyId(), group, stageName, ratio(stage.winRate(), "赢率"), order);
        }
        var result = groupView(actor, Map.of("id", group, "name", name));
        audit.record(actor, "crm_business.stage_create", "crm_business_stage_group", group, null, result);
        return result;
    }

    private Map<String, Object> groupView(CompanyAccess actor, Map<String, Object> row) {
        long id = number(row, "id");
        var result = ApiRows.row(row);
        result.put("stages", jdbc.queryForList("""
                SELECT id,name,win_rate,sort FROM crm_business_stage
                WHERE tenant_id=? AND company_id=? AND group_id=? ORDER BY sort,id
                """, actor.tenantId(), actor.companyId(), id).stream().map(ApiRows::row).toList());
        result.put("departmentIds", jdbc.queryForList("""
                SELECT department_id FROM crm_stage_department WHERE tenant_id=? AND company_id=? AND group_id=? ORDER BY department_id
                """, Long.class, actor.tenantId(), actor.companyId(), id).stream().map(String::valueOf).toList());
        return result;
    }

    Map<String, Object> list(CompanyAccess actor, String partnerId, String result, String cursor, int limit) {
        if (result != null && !Set.of("open", "won", "lost", "invalid").contains(result)) throw invalid("商机状态无效");
        Long partner = partnerId == null ? null : id(partnerId);
        var rows = jdbc.queryForList("""
                SELECT b.*,p.name partner_name,s.name stage_name,s.win_rate
                FROM crm_business b JOIN crm_customer_profile c ON c.tenant_id=b.tenant_id AND c.company_id=b.company_id AND c.partner_id=b.partner_id
                JOIN business_partner p ON p.tenant_id=b.tenant_id AND p.id=b.partner_id
                JOIN crm_business_stage s ON s.tenant_id=b.tenant_id AND s.company_id=b.company_id AND s.group_id=b.stage_group_id AND s.id=b.stage_id
                WHERE b.tenant_id=? AND b.company_id=? AND b.id<? AND (? IS NULL OR b.partner_id=?)
                  AND (? IS NULL OR b.result=?) AND (%s) ORDER BY b.id DESC LIMIT ?
                """.formatted(scope.customerFilter(actor, "c")), actor.tenantId(), actor.companyId(), cursor(cursor, limit),
                partner, partner, result, result, limit + 1);
        return page(rows, limit);
    }

    Map<String, Object> get(CompanyAccess actor, long id) {
        var row = source(actor, id, false, false);
        var result = ApiRows.row(row);
        result.put("lines", jdbc.queryForList("""
                SELECT l.id,l.line_no,l.sku_id,l.unit_id,l.qty,l.unit_price,l.discount_rate,l.amount,s.name sku_name,u.name unit_name
                FROM crm_business_line l JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.business_id=? ORDER BY l.line_no
                """, actor.tenantId(), actor.companyId(), id).stream().map(ApiRows::row).toList());
        var customer = scope.requireCustomer(actor, number(row, "partner_id"), false, false);
        result.put("editable", actor.hasPermission("crm_business:manage") && CrmScope.flag(customer.get("can_write"))
                && CrmScope.flag(customer.get("active_customer_role")) && "active".equals(customer.get("partner_status")));
        return result;
    }

    Map<String, Object> source(CompanyAccess actor, long id, boolean write, boolean lock) {
        String sql = "SELECT * FROM crm_business WHERE tenant_id=? AND company_id=? AND id=?";
        var row = one(jdbc.queryForList(sql, actor.tenantId(), actor.companyId(), id));
        scope.requireCustomer(actor, number(row, "partner_id"), write, lock);
        return lock ? one(jdbc.queryForList(sql + " FOR UPDATE", actor.tenantId(), actor.companyId(), id)) : row;
    }

    @Transactional
    public Map<String, Object> save(CompanyAccess actor, Long id, CrmCycleModels.Business input) {
        long partner = id(input.partnerId());
        var old = id == null ? null : source(actor, id, true, true);
        if (old != null && partner != number(old, "partner_id")) throw invalid("已保存的商机不能更换客户");
        var profile = scope.requireCustomer(actor, partner, true, true);
        if (old != null) {
            version(input.version(), old);
            open(old);
            if (id(input.stageGroupId()) != number(old, "stage_group_id") || id(input.stageId()) != number(old, "stage_id"))
                throw invalid("请通过阶段推进变更已保存商机的阶段");
        }
        return write(actor, id, input, profile, old);
    }

    private Map<String, Object> write(CompanyAccess actor, Long existing, CrmCycleModels.Business input,
                                      Map<String, Object> profile, Map<String, Object> old) {
        long group = id(input.stageGroupId()), stage = id(input.stageId());
        long department = old == null ? number(profile, "owner_department_id") : number(old, "owner_department_id");
        validateStage(actor, group, stage, department);
        var products = input.lines() == null ? List.<CrmCycleModels.Product>of() : input.lines();
        var lines = products.stream().map(line -> product(line)).toList();
        if (!lines.isEmpty()) catalog.requireActiveSkus(actor, lines.stream().map(ProductPrice::key).toList());
        var amount = input.expectedAmount() == null
                ? lines.stream().map(ProductPrice::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                : exact(input.expectedAmount(), 2, "预计金额");
        if (amount.precision() > 24) throw invalid("预计金额超出支持范围");
        String name = required(input.name(), 120, "商机名称");
        long id = existing == null ? Ids.next() : existing;
        if (existing == null) jdbc.update("""
                INSERT INTO crm_business(id,tenant_id,company_id,name,partner_id,stage_group_id,stage_id,expected_amount,
                  expected_date,owner_user_id,owner_department_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, id, actor.tenantId(), actor.companyId(), name, id(input.partnerId()), group, stage, amount,
                input.expectedDate(), number(profile, "owner_user_id"), department);
        else jdbc.update("""
                UPDATE crm_business SET name=?,expected_amount=?,expected_date=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """, name, amount, input.expectedDate(), actor.tenantId(), actor.companyId(), id);
        if (existing == null) stageEvent(actor, id, null, stage, false);
        jdbc.update("DELETE FROM crm_business_line WHERE tenant_id=? AND company_id=? AND business_id=?",
                actor.tenantId(), actor.companyId(), id);
        int lineNo = 0;
        for (var line : lines) jdbc.update("""
                INSERT INTO crm_business_line(id,tenant_id,company_id,business_id,line_no,sku_id,unit_id,qty,unit_price,discount_rate,amount)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, Ids.next(), actor.tenantId(), actor.companyId(), id, ++lineNo, line.key().skuId(), line.key().unitId(),
                line.qty(), line.price(), line.discount(), line.amount());
        var result = get(actor, id);
        audit.record(actor, existing == null ? "crm_business.create" : "crm_business.update", "crm_business", id, old, result);
        return result;
    }

    @Transactional
    public Map<String, Object> command(CompanyAccess actor, long id, String action, CrmCycleModels.Action input) {
        var row = source(actor, id, true, true);
        version(input.version(), row);
        open(row);
        long stage = number(row, "stage_id");
        String result = "open", reason = null;
        switch (action) {
            case "advance" -> {
                long group = number(row, "stage_group_id");
                long next = id(input.stageId());
                validateStage(actor, group, next, number(row, "owner_department_id"));
                if (next == stage) throw invalid("请选择不同的商机阶段");
                stageEvent(actor, id, stage, next, stageSort(actor, group, next) > stageSort(actor, group, stage));
                stage = next;
            }
            case "win" -> result = "won";
            case "lose", "invalidate" -> {
                result = action.equals("lose") ? "lost" : "invalid";
                reason = required(input.reason(), 300, "结束原因");
            }
            default -> throw invalid("不支持的商机操作");
        }
        jdbc.update("""
                UPDATE crm_business SET stage_id=?,result=?,lost_reason=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """, stage, result, reason, actor.tenantId(), actor.companyId(), id);
        if (result.equals("won")) jdbc.update("""
                UPDATE crm_customer_profile SET deal_status='sold',version=version+1
                WHERE tenant_id=? AND company_id=? AND partner_id=? AND deal_status<>'sold'
                """, actor.tenantId(), actor.companyId(), number(row, "partner_id"));
        var updated = get(actor, id);
        audit.record(actor, "crm_business." + action, "crm_business", id, row, updated);
        return updated;
    }

    private int stageSort(CompanyAccess actor, long group, long stage) {
        return jdbc.queryForObject("SELECT sort FROM crm_business_stage WHERE tenant_id=? AND company_id=? AND group_id=? AND id=?",
                Integer.class, actor.tenantId(), actor.companyId(), group, stage);
    }

    private void stageEvent(CompanyAccess actor, long id, Long previous, long next, boolean advanced) {
        jdbc.update("""
                INSERT INTO crm_business_stage_event(id,tenant_id,company_id,business_id,from_stage_id,to_stage_id,advanced)
                VALUES(?,?,?,?,?,?,?)
                """, Ids.next(), actor.tenantId(), actor.companyId(), id, previous, next, advanced);
    }

    private void validateStage(CompanyAccess actor, long group, long stage, long department) {
        one(jdbc.queryForList("""
                SELECT s.id FROM crm_business_stage s
                WHERE s.tenant_id=? AND s.company_id=? AND s.group_id=? AND s.id=?
                  AND (NOT EXISTS(SELECT 1 FROM crm_stage_department d WHERE d.tenant_id=s.tenant_id AND d.company_id=s.company_id AND d.group_id=s.group_id)
                       OR EXISTS(SELECT 1 FROM crm_stage_department d WHERE d.tenant_id=s.tenant_id AND d.company_id=s.company_id AND d.group_id=s.group_id AND d.department_id=?))
                """, actor.tenantId(), actor.companyId(), group, stage, department));
    }

    private void validateDepartment(CompanyAccess actor, long department) {
        one(jdbc.queryForList("SELECT id FROM org_department WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",
                actor.tenantId(), actor.companyId(), department));
    }

    private static void open(Map<String, Object> row) {
        if (!Objects.equals(row.get("result"), "open")) throw conflict("STATE_CONFLICT", "商机已结束");
    }

    private record ProductPrice(CatalogAvailability.SkuUnit key, BigDecimal qty, BigDecimal price,
                                BigDecimal discount, BigDecimal amount) {}

    private static ProductPrice product(CrmCycleModels.Product input) {
        var qty = positive(input.qty(), 6, "数量");
        var price = exact(input.unitPrice(), 8, "单价");
        var discount = input.discountRate() == null ? BigDecimal.ZERO : ratio(input.discountRate(), "折扣比例");
        var amount = qty.multiply(price).multiply(BigDecimal.ONE.subtract(discount)).setScale(2, RoundingMode.HALF_UP);
        if (amount.precision() > 24) throw invalid("明细金额超出支持范围");
        return new ProductPrice(new CatalogAvailability.SkuUnit(id(input.skuId()), id(input.unitId())), qty, price, discount, amount);
    }
}
