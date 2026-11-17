package cn.erp.pricing;

import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
class PriceListService {
    private static final int MAX_ITEMS = 500;
    private static final int MAX_TARGETS = 100;
    private static final String HEADER = "SELECT id,name,currency,price_includes_tax,valid_from,valid_to,priority,status,version FROM price_list";
    private static final String SCOPE = " WHERE tenant_id=? AND company_id=?";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CatalogAvailability catalog;
    private final ReferenceAvailability partners;
    private final AuditService audit;

    PriceListService(JdbcTemplate jdbc, ObjectMapper json, CatalogAvailability catalog,
                     ReferenceAvailability partners, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
        this.partners = partners;
        this.audit = audit;
    }

    record Item(String skuId, String unitId, String minQty, String unitPrice) {}
    record Target(String targetType, String targetValue) {}
    record Input(String name, Boolean priceIncludesTax, LocalDate validFrom, LocalDate validTo,
                 Integer priority, String status, Integer version, List<Item> items, List<Target> targets) {}
    record Header(String id, String name, String currency, boolean priceIncludesTax, LocalDate validFrom,
                  LocalDate validTo, int priority, String status, int version) {}
    record View(Header header, List<Item> items, List<Target> targets) {}
    record Page(List<Header> items, String nextCursor) {}
    record Selection(int lineNo, String skuId, String unitId, String qty) {}
    record Quote(BigDecimal price, boolean includesTax, String source) {}

    Page list(CompanyAccess actor, String cursor, int limit) {
        if (limit < 1 || limit > 200) throw PricingDecimals.invalid("每页条数须在1到200之间");
        var rows = jdbc.query(HEADER + SCOPE + " AND id<? ORDER BY id DESC LIMIT ?", this::header,
                actor.tenantId(), actor.companyId(), cursor == null ? Long.MAX_VALUE : id(cursor), limit + 1);
        var result = rows.stream().limit(limit).toList();
        return new Page(result, rows.size() > limit ? result.getLast().id() : null);
    }

    View get(CompanyAccess actor, long id) {
        var header = find(actor, id, false);
        var items = jdbc.query("SELECT sku_id,unit_id,min_qty,unit_price FROM price_list_item" + SCOPE
                        + " AND price_list_id=? ORDER BY sku_id,unit_id,min_qty",
                (row, index) -> new Item(row.getString(1), row.getString(2), row.getBigDecimal(3).toPlainString(),
                        row.getBigDecimal(4).toPlainString()), actor.tenantId(), actor.companyId(), id);
        var targets = jdbc.query("SELECT target_type,target_value FROM price_list_target" + SCOPE
                        + " AND price_list_id=? ORDER BY target_type,target_value",
                (row, index) -> new Target(row.getString(1), row.getString(2)), actor.tenantId(), actor.companyId(), id);
        return new View(header, items, targets);
    }

    @Transactional
    public View save(CompanyAccess actor, Long existing, Input input) {
        validate(input);
        long id = existing == null ? Ids.next() : existing;
        Header before = existing == null ? null : find(actor, id, true);
        if (before != null && (input.version() == null || before.version() != input.version()))
            throw new ApiException(409, "VERSION_CONFLICT", "价格表已修改，请刷新后重试");
        var items = parseItems(actor, input.items());
        var targets = parseTargets(actor, input.targets());
        if (before == null) jdbc.update("""
                INSERT INTO price_list(id,tenant_id,company_id,name,price_includes_tax,valid_from,valid_to,priority,status)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, id, actor.tenantId(), actor.companyId(), input.name().strip(), input.priceIncludesTax(),
                input.validFrom(), input.validTo(), input.priority(), input.status());
        else {
            jdbc.update("""
                    UPDATE price_list SET name=?,price_includes_tax=?,valid_from=?,valid_to=?,priority=?,status=?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """, input.name().strip(), input.priceIncludesTax(), input.validFrom(), input.validTo(),
                    input.priority(), input.status(), actor.tenantId(), actor.companyId(), id);
            jdbc.update("DELETE FROM price_list_item" + SCOPE + " AND price_list_id=?", actor.tenantId(), actor.companyId(), id);
            jdbc.update("DELETE FROM price_list_target" + SCOPE + " AND price_list_id=?", actor.tenantId(), actor.companyId(), id);
        }
        jdbc.batchUpdate("""
                INSERT INTO price_list_item(id,tenant_id,company_id,price_list_id,sku_id,unit_id,min_qty,unit_price)
                VALUES(?,?,?,?,?,?,?,?)
                """, items.stream().map(item -> new Object[]{Ids.next(), actor.tenantId(), actor.companyId(), id,
                item.key().skuId(), item.key().unitId(), item.min(), item.price()}).toList());
        jdbc.batchUpdate("""
                INSERT INTO price_list_target(id,tenant_id,company_id,price_list_id,target_type,target_value)
                VALUES(?,?,?,?,?,?)
                """, targets.stream().map(target -> new Object[]{Ids.next(), actor.tenantId(), actor.companyId(), id,
                target.targetType(), target.targetValue()}).toList());
        var saved = get(actor, id);
        audit.record(actor, before == null ? "price_list.create" : "price_list.update", "price_list", id, before, saved.header());
        return saved;
    }

    Map<Integer, Quote> quotes(CompanyAccess actor, long partner, LocalDate businessDate, List<Selection> selections) {
        if (selections.isEmpty()) return Map.of();
        var result = new HashMap<Integer, Quote>();
        jdbc.query("""
                WITH matches AS (
                  SELECT req.line_no,i.unit_price,p.price_includes_tax,t.target_type,
                    ROW_NUMBER() OVER (PARTITION BY req.line_no ORDER BY
                      CASE t.target_type WHEN 'customer' THEN 1 WHEN 'customer_level' THEN 2 ELSE 3 END,
                      p.priority DESC,i.min_qty DESC,p.id DESC) position_no
                  FROM JSON_TABLE(?, '$[*]' COLUMNS(line_no INT PATH '$.lineNo',
                    sku_id BIGINT PATH '$.skuId',unit_id BIGINT PATH '$.unitId',qty DECIMAL(24,6) PATH '$.qty')) req
                  JOIN price_list_item i ON i.tenant_id=? AND i.company_id=? AND i.sku_id=req.sku_id
                    AND i.unit_id=req.unit_id AND i.min_qty<=req.qty
                  JOIN price_list p ON p.tenant_id=i.tenant_id AND p.company_id=i.company_id AND p.id=i.price_list_id
                  JOIN price_list_target t ON t.tenant_id=p.tenant_id AND t.company_id=p.company_id AND t.price_list_id=p.id
                  LEFT JOIN crm_customer_profile c ON c.tenant_id=p.tenant_id AND c.company_id=p.company_id AND c.partner_id=?
                  WHERE p.status='active' AND ? BETWEEN p.valid_from AND p.valid_to
                    AND (t.target_type='all' OR t.target_type='customer' AND t.target_value=?
                      OR t.target_type='customer_level' AND t.target_value=c.level)
                ) SELECT line_no,unit_price,price_includes_tax,target_type FROM matches WHERE position_no=1
                """, row -> { result.put(row.getInt(1), new Quote(row.getBigDecimal(2), row.getBoolean(3), row.getString(4))); },
                json.writeValueAsString(selections), actor.tenantId(), actor.companyId(), partner, date(businessDate), Long.toString(partner));
        return result;
    }

    private Header find(CompanyAccess actor, long id, boolean lock) {
        return jdbc.query(HEADER + SCOPE + " AND id=?" + (lock ? " FOR UPDATE" : ""), this::header,
                actor.tenantId(), actor.companyId(), id).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "价格表不存在或无权查看"));
    }

    private Header header(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Header(row.getString(1), row.getString(2), row.getString(3), row.getBoolean(4),
                row.getObject(5, LocalDate.class), row.getObject(6, LocalDate.class), row.getInt(7), row.getString(8), row.getInt(9));
    }

    private record Tier(CatalogAvailability.SkuUnit key, BigDecimal min, BigDecimal price) {}

    private List<Tier> parseItems(CompanyAccess actor, List<Item> items) {
        var tiers = new ArrayList<Tier>();
        var seen = new HashSet<List<Object>>();
        for (var item : items) {
            if (item == null) throw PricingDecimals.invalid("价格明细不能为空");
            var key = new CatalogAvailability.SkuUnit(id(item.skuId()), id(item.unitId()));
            var min = PricingDecimals.input(item.minQty(), 24, 6, "起订数量", false);
            var price = PricingDecimals.input(item.unitPrice(), 24, 8, "协议单价", false);
            if (!seen.add(List.of(key, min))) throw PricingDecimals.invalid("同一商品、单位和起订数量只能设置一个价格");
            tiers.add(new Tier(key, min, price));
        }
        catalog.requireActiveSkus(actor, tiers.stream().map(Tier::key).toList());
        return tiers;
    }

    private Set<Target> parseTargets(CompanyAccess actor, List<Target> inputs) {
        var targets = new LinkedHashSet<Target>();
        for (var target : inputs) {
            if (target == null || target.targetType() == null || target.targetValue() == null)
                throw PricingDecimals.invalid("请设置价格适用对象");
            String value = target.targetValue().strip();
            switch (target.targetType()) {
                case "all" -> { if (!value.isEmpty()) throw PricingDecimals.invalid("通用价格无需填写对象"); }
                case "customer" -> partners.requirePartner(actor, id(value), "customer");
                case "customer_level" -> {
                    if (!value.matches("[A-Za-z0-9_\\-]{1,64}")) throw PricingDecimals.invalid("客户级别编码无效");
                }
                default -> throw PricingDecimals.invalid("价格适用类型无效");
            }
            targets.add(new Target(target.targetType(), value));
        }
        return targets;
    }

    private static void validate(Input input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().strip().length() > 100
                || input.priceIncludesTax() == null || input.validFrom() == null || input.validTo() == null
                || input.validFrom().getYear() < 1000 || input.validTo().getYear() > 9999
                || input.validFrom().isAfter(input.validTo()) || input.priority() == null
                || input.priority() < 0 || input.priority() > 9999 || input.status() == null
                || !List.of("active", "disabled").contains(input.status())
                || input.items() == null || input.items().isEmpty() || input.items().size() > MAX_ITEMS
                || input.targets() == null || input.targets().isEmpty() || input.targets().size() > MAX_TARGETS) {
            throw PricingDecimals.invalid("请填写名称、含税口径、有效期、优先级、适用对象和价格明细");
        }
    }

    static LocalDate date(LocalDate day) {
        return day == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : day;
    }

    static long id(String value) {
        try {
            if (value != null && value.matches("[1-9][0-9]{0,18}")) return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw PricingDecimals.invalid("编号超出允许范围");
        }
        throw PricingDecimals.invalid("请选择有效编号");
    }
}
