package cn.erp.ar;

import java.time.LocalDate;
import java.util.*;
import jakarta.validation.constraints.*;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import cn.erp.identity.*;
import cn.erp.shared.*;

import static cn.erp.ar.ArSupport.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class CollectionService {
    private final ArSupport s;
    private final AccessService access;
    private final ApplicationEventPublisher events;

    CollectionService(ArSupport s, AccessService access, ApplicationEventPublisher events) {
        this.s = s;
        this.access = access;
        this.events = events;
    }

    public record Plan(
            @Positive(message = "请选择未清项") long openItemId,
            @Positive(message = "请选择负责人") long ownerId,
            @NotNull(message = "请选择计划日期") LocalDate planDate,
            @NotBlank(message = "请输入计划金额") String planAmount) {}

    public record Followup(
            @NotNull(message = "请提供版本号") Long version,
            @NotBlank(message = "请填写跟进内容") String content,
            LocalDate promisedDate,
            String promisedAmount,
            String status) {}

    static void require(CompanyAccess a, String permission) {
        if (!a.allWarehouses() || !a.hasPermission(permission))
            throw new ApiException(403, "FORBIDDEN", "没有执行催收操作的权限");
    }

    public List<Map<String, Object>> list(CompanyAccess a) {
        require(a, "collection:view");
        return s
                .jdbc
                .queryForList(
                        "SELECT p.*,b.name partner_name,u.display_name owner_name,i.open_amount"
                            + " FROM ar_collection_plan p JOIN partner_open_item i ON"
                            + " i.tenant_id=p.tenant_id AND i.company_id=p.company_id AND"
                            + " i.id=p.open_item_id JOIN business_partner b ON"
                            + " b.tenant_id=p.tenant_id AND b.id=p.partner_id JOIN sys_user u ON"
                            + " u.tenant_id=p.tenant_id AND u.id=p.owner_id WHERE p.tenant_id=? AND"
                            + " p.company_id=? AND "
                                + OrgOwnership.itemCondition(a, "i")
                                + " ORDER BY p.plan_date,p.id LIMIT 500",
                        a.tenantId(),
                        a.companyId())
                .stream()
                .map(ApiRows::row)
                .toList();
    }

    public Map<String, Object> get(CompanyAccess a, long id) {
        require(a, "collection:view");
        var row = s.row(a, "ar_collection_plan", id, false);
        OrgOwnership.requireItem(s.jdbc, a, row.get("open_item_id"));
        var result = ApiRows.row(row);
        result.put(
                "followups",
                s
                        .jdbc
                        .queryForList(
                                "SELECT * FROM ar_collection_followup WHERE tenant_id=? AND"
                                        + " company_id=? AND plan_id=? ORDER BY id",
                                a.tenantId(),
                                a.companyId(),
                                id)
                        .stream()
                        .map(ApiRows::row)
                        .toList());
        return result;
    }

    public List<Map<String, Object>> items(CompanyAccess a) {
        require(a, "collection:view");
        return s
                .jdbc
                .queryForList(
                        "SELECT i.*,b.name partner_name FROM partner_open_item i JOIN"
                            + " business_partner b ON b.tenant_id=i.tenant_id AND b.id=i.partner_id"
                            + " WHERE i.tenant_id=? AND i.company_id=? AND i.item_type='receivable'"
                            + " AND i.direction='D' AND i.open_amount>0 AND "
                                + OrgOwnership.itemCondition(a, "i")
                                + " ORDER BY i.aging_date,i.id LIMIT 1000",
                        a.tenantId(),
                        a.companyId())
                .stream()
                .map(ApiRows::row)
                .toList();
    }

    @Transactional
    public Map<String, Object> create(CompanyAccess a, String key, Plan input) {
        require(a, "collection:edit");
        OrgOwnership.requireItem(s.jdbc, a, input.openItemId());
        return s.execute(
                a,
                "collection.create",
                key,
                input,
                () -> {
                    var item = s.item(a, input.openItemId(), true);
                    var owner = access.forUser(a.tenantId(), input.ownerId(), a.companyId());
                    if (owner == null || !owner.hasPermission("collection:view"))
                        throw invalid("负责人须为当前公司有效用户并拥有催收查看权限");
                    OrgOwnership.requireItem(s.jdbc, owner, input.openItemId());
                    var amount = money(input.planAmount());
                    if (amount.signum() <= 0
                            || amount.compareTo(decimal(item.get("open_amount"))) > 0
                            || input.planDate() == null) throw invalid("计划金额须大于零且不超过未清余额，并填写计划日期");
                    long id = Ids.next();
                    s.jdbc.update(
                            "INSERT INTO"
                                + " ar_collection_plan(id,tenant_id,company_id,partner_id,open_item_id,owner_id,plan_date,plan_amount,created_by)"
                                + " VALUES(?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenantId(),
                            a.companyId(),
                            item.get("partner_id"),
                            input.openItemId(),
                            input.ownerId(),
                            input.planDate(),
                            amount,
                            a.userId());
                    var after = get(a, id);
                    s.audit.record(a, "collection.create", "ar_collection_plan", id, null, after);
                    return after;
                });
    }

    @Transactional
    public Map<String, Object> follow(CompanyAccess a, long id, String key, Followup input) {
        require(a, "collection:edit");
        var first = s.row(a, "ar_collection_plan", id, false);
        OrgOwnership.requireItem(s.jdbc, a, first.get("open_item_id"));
        return s.execute(
                a,
                "collection.follow",
                key,
                Map.of("id", id, "input", input),
                () -> {
                    var before = s.row(a, "ar_collection_plan", id, true);
                    version(input.version(), before);
                    if (!before.get("status").equals("open")) throw invalid("已结束计划不能继续跟进");
                    String status = input.status() == null ? "open" : input.status();
                    if (!Set.of("open", "completed", "cancelled").contains(status))
                        throw invalid("催收状态无效");
                    s.jdbc.update(
                            "INSERT INTO"
                                + " ar_collection_followup(id,tenant_id,company_id,plan_id,content,promised_date,promised_amount,created_by)"
                                + " VALUES(?,?,?,?,?,?,?,?)",
                            Ids.next(),
                            a.tenantId(),
                            a.companyId(),
                            id,
                            text(input.content(), 1000, "跟进记录"),
                            input.promisedDate(),
                            input.promisedAmount() == null ? null : money(input.promisedAmount()),
                            a.userId());
                    s.jdbc.update(
                            "UPDATE ar_collection_plan SET status=?,version=version+1 WHERE"
                                    + " tenant_id=? AND company_id=? AND id=?",
                            status,
                            a.tenantId(),
                            a.companyId(),
                            id);
                    var after = get(a, id);
                    s.audit.record(a, "collection.follow", "ar_collection_plan", id, before, after);
                    return after;
                });
    }

    @Transactional
    public int remind(Long tenant) {
        var rows =
                s.jdbc.queryForList(
                        "SELECT p.*,DATEDIFF(business_clock.today,p.plan_date) days FROM"
                            + " ar_collection_plan p CROSS JOIN(SELECT ? today) business_clock"
                            + " WHERE (? IS NULL OR p.tenant_id=?) AND p.status='open' AND"
                            + " EXISTS(SELECT 1 FROM partner_open_item i WHERE"
                            + " i.tenant_id=p.tenant_id AND i.company_id=p.company_id AND"
                            + " i.id=p.open_item_id AND i.open_amount>0) AND"
                            + " (DATEDIFF(business_clock.today,p.plan_date) IN(-3,0) OR"
                            + " DATEDIFF(business_clock.today,p.plan_date)>0 AND"
                            + " MOD(DATEDIFF(business_clock.today,p.plan_date),7)=0) AND"
                            + " COALESCE(p.last_reminder_key,'')<>CAST(business_clock.today AS"
                            + " CHAR) ORDER BY p.plan_date,p.id LIMIT 100 FOR UPDATE SKIP LOCKED",
                        today(),
                        tenant,
                        tenant);
        int count = 0;
        for (var row : rows) {
            var actor =
                    access.forUser(
                            number(row.get("tenant_id")),
                            number(row.get("owner_id")),
                            number(row.get("company_id")));
            if (actor == null || !actor.hasPermission("collection:view")) continue;
            try {
                OrgOwnership.requireItem(s.jdbc, actor, row.get("open_item_id"));
            } catch (ApiException error) {
                if (Set.of(403, 404).contains(error.getStatus())) continue;
                throw error;
            }
            long id = number(row.get("id"));
            String date = today().toString();
            events.publishEvent(
                    new CollectionNotice(
                            actor.tenantId(),
                            actor.companyId(),
                            actor.userId(),
                            id,
                            "collection:" + id + ":" + date));
            s.jdbc.update(
                    "UPDATE ar_collection_plan SET last_reminder_key=? WHERE tenant_id=? AND"
                            + " company_id=? AND id=?",
                    date,
                    actor.tenantId(),
                    actor.companyId(),
                    id);
            count++;
        }
        return count;
    }
}
