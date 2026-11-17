package cn.erp.crm;

import cn.erp.identity.CompanyAccess;
import cn.erp.pricing.PricingModels;
import cn.erp.pricing.PricingService;
import cn.erp.salesorder.SalesOrderModels;
import cn.erp.salesorder.SalesOrderQueries;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import cn.erp.shared.WorkflowBusiness;
import cn.erp.shared.WorkflowContext;
import cn.erp.shared.WorkflowPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.crm.CrmCycleData.*;

@Service
@Transactional(readOnly = true)
class CrmContractService implements WorkflowBusiness {
    static final String TYPE = "crm_contract";
    private final JdbcTemplate jdbc;
    private final CrmScope scope;
    private final CrmOpportunityService business;
    private final PricingService pricing;
    private final WorkflowPort workflow;
    private final SalesOrderService orders;
    private final SalesOrderQueries orderQueries;
    private final AuditService audit;
    private final ObjectMapper json;

    CrmContractService(JdbcTemplate jdbc, CrmScope scope, CrmOpportunityService business, PricingService pricing,
                       WorkflowPort workflow, SalesOrderService orders, SalesOrderQueries orderQueries,
                       AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.business = business;
        this.pricing = pricing;
        this.workflow = workflow;
        this.orders = orders;
        this.orderQueries = orderQueries;
        this.audit = audit;
        this.json = json;
    }

    Map<String, Object> list(CompanyAccess actor, String partnerId, String status, String cursor, int limit) {
        if (status != null && !Set.of("draft", "pending", "approved", "rejected", "cancelled", "finished").contains(status))
            throw invalid("合同状态无效");
        Long partner = partnerId == null ? null : id(partnerId);
        var rows = jdbc.queryForList("""
                SELECT k.*,k.no document_no,p.name partner_name FROM crm_contract k
                JOIN crm_customer_profile c ON c.tenant_id=k.tenant_id AND c.company_id=k.company_id AND c.partner_id=k.partner_id
                JOIN business_partner p ON p.tenant_id=k.tenant_id AND p.id=k.partner_id
                WHERE k.tenant_id=? AND k.company_id=? AND k.id<? AND (? IS NULL OR k.partner_id=?)
                  AND (? IS NULL OR k.status=?) AND (%s) ORDER BY k.id DESC LIMIT ?
                """.formatted(scope.customerFilter(actor, "c")), actor.tenantId(), actor.companyId(), cursor(cursor, limit),
                partner, partner, status, status, limit + 1);
        return page(rows, limit);
    }

    Map<String, Object> source(CompanyAccess actor, long id, boolean write, boolean lock) {
        String sql = "SELECT * FROM crm_contract WHERE tenant_id=? AND company_id=? AND id=?";
        var row = one(jdbc.queryForList(sql, actor.tenantId(), actor.companyId(), id));
        scope.requireCustomer(actor, number(row, "partner_id"), write, lock);
        return lock ? one(jdbc.queryForList(sql + " FOR UPDATE", actor.tenantId(), actor.companyId(), id)) : row;
    }

    Map<String, Object> get(CompanyAccess actor, long id) {
        var row = source(actor, id, false, false);
        var result = ApiRows.row(row);
        result.put("documentNo", row.get("no"));
        var allocated = allocations(actor, id);
        result.put("lines", lines(actor, id).stream().map(line -> lineView(line, allocated)).toList());
        result.put("salesOrderIds", visibleOrders(actor, id));
        result.put("allowedActions", actions(actor, row));
        return result;
    }

    private List<String> actions(CompanyAccess actor, Map<String, Object> row) {
        var customer = scope.requireCustomer(actor, number(row, "partner_id"), false, false);
        if (!CrmScope.flag(customer.get("can_write")) || !CrmScope.flag(customer.get("active_customer_role"))
                || !"active".equals(customer.get("partner_status"))) return List.of();
        var actions = new ArrayList<String>();
        String status = row.get("status").toString();
        if (actor.hasPermission("crm_contract:manage") && Set.of("draft", "rejected").contains(status)) {
            actions.add("update");
            actions.add("submit");
            actions.add("cancel");
        }
        if (status.equals("approved") && actor.hasPermission("crm_contract:order") && actor.hasPermission("sales_order:create"))
            actions.add("create_sales_order");
        return List.copyOf(actions);
    }

    private List<String> visibleOrders(CompanyAccess actor, long id) {
        if (!actor.hasPermission("sales_order:view")) return List.of();
        var result = new ArrayList<String>();
        for (Long order : jdbc.queryForList("""
                SELECT order_id FROM crm_contract_order WHERE tenant_id=? AND company_id=? AND contract_id=? ORDER BY order_id DESC
                """, Long.class, actor.tenantId(), actor.companyId(), id)) {
            try {
                orderQueries.header(actor, order, false);
                result.add(Long.toString(order));
            } catch (ApiException denied) {
                if (!Set.of(403, 404).contains(denied.getStatus())) throw denied;
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> lineView(Map<String, Object> line, Map<Long, Allocation> allocated) {
        var result = ApiRows.row(line);
        var used = allocated.getOrDefault(number(line, "id"), Allocation.ZERO);
        result.put("orderedQty", used.quantity().toPlainString());
        result.put("remainingQty", value(line, "qty").subtract(used.quantity()).toPlainString());
        return result;
    }

    private List<Map<String, Object>> lines(CompanyAccess actor, long id) {
        return jdbc.queryForList("""
                SELECT l.*,s.name sku_name,u.name unit_name FROM crm_contract_line l
                JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.contract_id=? ORDER BY l.line_no
                """, actor.tenantId(), actor.companyId(), id);
    }

    @Transactional
    public Map<String, Object> save(CompanyAccess actor, Long existing, CrmCycleModels.Contract input) {
        long partner = id(input.partnerId());
        var old = existing == null ? null : source(actor, existing, true, true);
        var profile = scope.requireCustomer(actor, partner, true, true);
        if (old != null) {
            version(input.version(), old);
            requireStatus(old, "draft", "rejected");
            if (partner != number(old, "partner_id")) throw invalid("已保存的合同不能更换客户");
        }
        if (input.startDate().isAfter(input.endDate())) throw invalid("合同结束日不能早于开始日");
        Long opportunity = input.businessId() == null ? null : id(input.businessId());
        Long contact = input.signerContactId() == null ? null : id(input.signerContactId());
        validateReferences(actor, partner, opportunity, contact);
        var preview = contractPricing(actor, input);
        long id = existing == null ? Ids.next() : existing;
        writeHeader(actor, id, input, profile, opportunity, contact, preview, old == null);
        writeLines(actor, id, input.lines(), preview);
        var result = get(actor, id);
        audit.record(actor, old == null ? "crm_contract.create" : "crm_contract.update", TYPE, id, old, result);
        return result;
    }

    private void validateReferences(CompanyAccess actor, long partner, Long opportunity, Long contact) {
        if (opportunity != null) {
            if (!actor.hasPermission("crm_business:view")) throw forbidden("关联商机需要查看商机的权限");
            var row = business.source(actor, opportunity, false, false);
            if (number(row, "partner_id") != partner || !"won".equals(row.get("result")))
                throw invalid("合同只能关联同一客户已赢单的商机");
        }
        if (contact != null) one(jdbc.queryForList("SELECT id FROM partner_contact WHERE tenant_id=? AND partner_id=? AND id=?",
                actor.tenantId(), partner, contact));
    }

    private PricingModels.Preview contractPricing(CompanyAccess actor, CrmCycleModels.Contract input) {
        var permissions = new HashSet<>(actor.permissions());
        permissions.add("sales_order:create");
        var calculator = new CompanyAccess(actor.tenantId(), actor.userId(), actor.companyId(), actor.allWarehouses(),
                actor.warehouseIds(), permissions, actor.orgScope());
        var rows = input.lines().stream().map(line -> new PricingModels.PreviewLine(line.skuId(), line.unitId(), null,
                line.qty(), line.unitPrice(), null, null, line.discountRate(), line.taxRate())).toList();
        return pricing.preview(calculator, new PricingModels.PreviewRequest("sales_order", input.partnerId(), null,
                input.priceTaxInclusive(), null, rows, List.of(), input.signDate()));
    }

    private void writeHeader(CompanyAccess actor, long id, CrmCycleModels.Contract input, Map<String, Object> profile,
                             Long opportunity, Long contact, PricingModels.Preview preview, boolean create) {
        var name = required(input.name(), 120, "合同名称");
        if (create) jdbc.update("""
                INSERT INTO crm_contract(id,tenant_id,company_id,no,name,partner_id,business_id,signer_contact_id,
                  owner_user_id,owner_department_id,sign_date,start_date,end_date,price_tax_inclusive,total_amount,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, actor.tenantId(), actor.companyId(), "CRM-" + id, name, id(input.partnerId()), opportunity, contact,
                number(profile, "owner_user_id"), number(profile, "owner_department_id"), input.signDate(), input.startDate(),
                input.endDate(), preview.priceTaxInclusive(), preview.totalAmount(), actor.userId());
        else jdbc.update("""
                UPDATE crm_contract SET name=?,business_id=?,signer_contact_id=?,sign_date=?,start_date=?,end_date=?,
                  price_tax_inclusive=?,total_amount=?,status='draft',version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """, name, opportunity, contact, input.signDate(), input.startDate(), input.endDate(), preview.priceTaxInclusive(),
                preview.totalAmount(), actor.tenantId(), actor.companyId(), id);
    }

    private void writeLines(CompanyAccess actor, long id, List<CrmCycleModels.Product> input, PricingModels.Preview preview) {
        jdbc.update("DELETE FROM crm_contract_line WHERE tenant_id=? AND company_id=? AND contract_id=?",
                actor.tenantId(), actor.companyId(), id);
        for (int index = 0; index < preview.lines().size(); index++) {
            var line = preview.lines().get(index);
            BigDecimal discount = input.get(index).discountRate() == null ? BigDecimal.ZERO
                    : ratio(input.get(index).discountRate(), "折扣比例");
            BigDecimal minimum = jdbc.queryForObject("SELECT min_sale_price FROM sku WHERE tenant_id=? AND id=?",
                    BigDecimal.class, actor.tenantId(), id(line.skuId()));
            boolean below = minimum != null && new BigDecimal(line.totalAmount()).compareTo(
                    minimum.multiply(new BigDecimal(line.baseQty())).setScale(2, RoundingMode.HALF_UP)) < 0;
            jdbc.update("""
                    INSERT INTO crm_contract_line(id,tenant_id,company_id,contract_id,line_no,sku_id,unit_id,qty,unit_price,
                      tax_rate,discount_rate,total_amount,below_min_price) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Ids.next(), actor.tenantId(), actor.companyId(), id, line.lineNo(), id(line.skuId()), id(line.unitId()),
                    line.qty(), line.unitPrice(), line.taxRate(), discount, line.totalAmount(), below);
        }
    }

    @Transactional
    public Map<String, Object> command(CompanyAccess actor, long id, String action, CrmCycleModels.Action input) {
        if (Set.of("approve", "reject").contains(action)) {
            source(actor, id, false, false);
            throw conflict("CRM_WORKFLOW_REQUIRED", "请在审批中心处理合同");
        }
        var row = source(actor, id, true, true);
        version(input.version(), row);
        switch (action) {
            case "submit" -> {
                requireStatus(row, "draft", "rejected");
                setStatus(actor, id, "pending");
                workflow.start(actor, TYPE, id, input.workflowAssignees());
                var submitted = source(actor, id, false, false);
                if (number(submitted, "wf_instance_id") == 0)
                    throw conflict("CRM_WORKFLOW_REQUIRED", "请先发布并绑定适用的合同审批流程");
            }
            case "cancel" -> {
                workflow.guard(actor, TYPE, id, "cancel");
                requireStatus(row, "draft", "rejected");
                setStatus(actor, id, "cancelled");
            }
            default -> throw invalid("不支持的合同操作");
        }
        var result = get(actor, id);
        audit.record(actor, "crm_contract." + action, TYPE, id, row, result);
        return result;
    }

    @Override public String businessType() { return TYPE; }

    @Override public Map<String, Object> snapshot(ActorScope actor, long id) {
        var company = company(actor);
        var row = source(company, id, false, false);
        var profile = scope.requireCustomer(company, number(row, "partner_id"), false, false);
        var terms = lines(company, id);
        BigDecimal discount = terms.stream().map(line -> value(line, "discount_rate")).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        return Map.of("documentNo", row.get("no"), "title", row.get("name"), "createdBy", row.get("created_by"),
                "ownerDeptId", row.get("owner_department_id"), "customerId", row.get("partner_id"),
                "salesUserId", row.get("owner_user_id"), "totalAmount", row.get("total_amount"), "discountRate", discount,
                "customerLevel", Objects.toString(profile.get("level"), ""),
                "belowMinPrice", terms.stream().anyMatch(line -> flag(line.get("below_min_price"))));
    }

    @Override @Transactional
    public void decide(ActorScope actor, long id, String outcome, String comment, String key) {
        var company = company(actor);
        if (!WorkflowContext.allows(actor, TYPE, id)) throw forbidden("合同必须通过审批流程处理");
        var row = source(company, id, false, true);
        requireStatus(row, "pending");
        if (Set.of("approved", "rejected").contains(outcome)
                && (!company.hasPermission("crm_contract:approve") || number(row, "created_by") == company.userId()))
            throw forbidden("合同审批需要有权限的其他操作人处理");
        String status = switch (outcome) {
            case "approved", "rejected", "cancelled" -> outcome;
            case "terminated" -> "cancelled";
            default -> throw invalid("合同审批结果无效");
        };
        setStatus(company, id, status);
        audit.record(actor, "crm_contract." + outcome, TYPE, id, row, Map.of("status", status));
    }

    private CompanyAccess company(ActorScope actor) {
        if (actor instanceof CompanyAccess company) return company;
        throw forbidden("合同审批缺少当前公司的授权上下文");
    }

    private void setStatus(CompanyAccess actor, long id, String status) {
        jdbc.update("""
                UPDATE crm_contract SET status=?,version=version+1,approved_at=IF(?='approved',UTC_TIMESTAMP(3),approved_at)
                WHERE tenant_id=? AND company_id=? AND id=?
                """, status, status, actor.tenantId(), actor.companyId(), id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> createOrder(CompanyAccess actor, long contractId, String key, CrmCycleModels.CreateOrder input) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("请提供有效的幂等键");
        if (!actor.hasPermission("sales_order:create")) throw forbidden("没有创建销售订单的权限");
        var contract = source(actor, contractId, true, true);
        String digest = hash(json.writeValueAsString(input));
        var requests = jdbc.queryForList("""
                SELECT order_id,operator_id,request_hash FROM crm_contract_order
                WHERE tenant_id=? AND company_id=? AND contract_id=? AND request_key=?
                """, actor.tenantId(), actor.companyId(), contractId, key);
        if (!requests.isEmpty()) return replayOrder(actor, requests.getFirst(), digest);
        requireStatus(contract, "approved");
        return createAllocatedOrder(actor, contract, key, digest, input);
    }

    private Map<String, Object> replayOrder(CompanyAccess actor, Map<String, Object> request, String digest) {
        if (number(request, "operator_id") != actor.userId()) throw forbidden("只能重试本人提交的生成订单请求");
        if (!digest.equals(request.get("request_hash"))) throw conflict("IDEMPOTENCY_KEY_REUSED", "同一请求标识对应了不同内容");
        return orderQueries.get(actor, number(request, "order_id"));
    }

    private Map<String, Object> createAllocatedOrder(CompanyAccess actor, Map<String, Object> contract, String key,
                                                     String digest, CrmCycleModels.CreateOrder input) {
        long contractId = number(contract, "id");
        var byId = new HashMap<Long, Map<String, Object>>();
        lines(actor, contractId).forEach(line -> byId.put(number(line, "id"), line));
        var used = allocations(actor, contractId);
        var selected = new LinkedHashMap<Long, BigDecimal>();
        var newLines = new ArrayList<SalesOrderModels.Line>();
        for (var request : input.lines()) {
            long contractLine = id(request.contractLineId());
            var terms = byId.get(contractLine);
            var quantity = positive(request.qty(), 6, "订购数量");
            if (terms == null || selected.putIfAbsent(contractLine, quantity) != null) throw invalid("合同明细不存在或重复");
            if (used.getOrDefault(contractLine, Allocation.ZERO).quantity().add(quantity).compareTo(value(terms, "qty")) > 0)
                throw conflict("CRM_CONTRACT_QTY_EXCEEDED", "累计订单数量不能超过合同数量");
            newLines.add(new SalesOrderModels.Line(null, terms.get("sku_id").toString(), Long.toString(id(request.warehouseId())),
                    terms.get("unit_id").toString(), quantity.toPlainString(), value(terms, "unit_price").toPlainString(),
                    null, null, value(terms, "discount_rate").toPlainString(), value(terms, "tax_rate").toPlainString()));
        }
        var draft = new SalesOrderModels.Input(contract.get("partner_id").toString(), input.businessDate(), input.deliveryDate(),
                flag(contract.get("price_tax_inclusive")), null, newLines, List.of(), "来源合同 " + contract.get("no"), null, null);
        var order = orders.createFromContract(actor, contractId, draft);
        long orderId = id(order.get("id").toString());
        jdbc.update("INSERT INTO crm_contract_order VALUES(?,?,?,?,?,?,?)", actor.tenantId(), actor.companyId(), contractId,
                orderId, key, digest, actor.userId());
        recordAllocations(actor, contractId, orderId, selected, byId, used);
        audit.record(actor, "crm_contract.create_sales_order", TYPE, contractId, null, Map.of("orderId", Long.toString(orderId)));
        return order;
    }

    private void recordAllocations(CompanyAccess actor, long contract, long order, Map<Long, BigDecimal> selected,
                                   Map<Long, Map<String, Object>> terms, Map<Long, Allocation> used) {
        var actual = jdbc.queryForList("""
                SELECT untaxed_amount,tax_amount FROM sales_order_line WHERE tenant_id=? AND company_id=? AND order_id=? ORDER BY line_no
                """, actor.tenantId(), actor.companyId(), order);
        int lineIndex = 0;
        for (var entry : selected.entrySet()) {
            var orderLine = actual.get(lineIndex++);
            long line = entry.getKey();
            BigDecimal amount = value(orderLine, "untaxed_amount").add(value(orderLine, "tax_amount"));
            if (used.getOrDefault(line, Allocation.ZERO).amount().add(amount).compareTo(value(terms.get(line), "total_amount")) > 0)
                throw conflict("CRM_CONTRACT_AMOUNT_EXCEEDED", "累计订单金额不能超过合同金额，请调整本次拆分数量");
            jdbc.update("INSERT INTO crm_contract_order_line VALUES(?,?,?,?,?,?,?)", actor.tenantId(), actor.companyId(),
                    contract, order, line, entry.getValue(), amount);
        }
    }

    private record Allocation(BigDecimal quantity, BigDecimal amount) {
        private static final Allocation ZERO = new Allocation(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private Map<Long, Allocation> allocations(CompanyAccess actor, long contract) {
        var rows = jdbc.queryForList("""
                SELECT a.contract_line_id,SUM(a.qty) qty,SUM(a.amount) amount FROM crm_contract_order_line a
                JOIN sales_order o ON o.tenant_id=a.tenant_id AND o.company_id=a.company_id AND o.id=a.order_id
                WHERE a.tenant_id=? AND a.company_id=? AND a.contract_id=? AND o.is_deleted=0 AND o.status<>'cancelled'
                GROUP BY a.contract_line_id
                """, actor.tenantId(), actor.companyId(), contract);
        var result = new HashMap<Long, Allocation>();
        for (var row : rows) result.put(number(row, "contract_line_id"), new Allocation(value(row, "qty"), value(row, "amount")));
        return result;
    }

    private static void requireStatus(Map<String, Object> row, String... states) {
        if (!Set.of(states).contains(row.get("status"))) throw conflict("STATE_CONFLICT", "当前合同状态不支持此操作");
    }

    private static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("运行环境缺少请求摘要算法", unavailable);
        }
    }
}
