package cn.erp.aitools;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AiBusinessLookup {
    private static final int MAX_CHOICES = 20;
    private final NamedParameterJdbcTemplate jdbc;

    AiBusinessLookup(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    enum Kind {
        CUSTOMER("business_partner"), SUPPLIER("business_partner"),
        SKU("sku"), WAREHOUSE("warehouse"), CASH("cash_account");

        final String table;
        Kind(String table) { this.table = table; }
    }

    record Match(long id, String code, String name, int priority) {
        Map<String,String> choice() { return Map.of("code",code,"name",name); }
    }

    static final class Ambiguous extends RuntimeException {
        private final List<Map<String,String>> candidates;
        Ambiguous(List<Match> matches) {
            super("找到多个可访问的匹配项，请按编码确认");
            candidates=matches.stream().limit(MAX_CHOICES).map(Match::choice).toList();
        }
        List<Map<String,String>> candidates() { return candidates; }
    }

    Match resolve(CompanyAccess actor, Kind kind, String term, Predicate<Match> visible) {
        if (term == null) return null;
        var args = new HashMap<String,Object>(Map.of("t", actor.tenantId(),"c", actor.companyId(),"q",term));
        String clause = switch (kind) {
            case CUSTOMER, SUPPLIER -> {
                args.put("role",kind==Kind.CUSTOMER?"customer":"supplier");
                yield " AND EXISTS(SELECT 1 FROM partner_role r WHERE r.tenant_id=p.tenant_id AND r.partner_id=p.id AND r.role=:role)";
            }
            case CASH -> " AND p.company_id=:c";
            case WAREHOUSE -> {
                args.put("warehouses",actor.warehouseIds().isEmpty()?Set.of(-1L):actor.warehouseIds());
                yield " AND p.company_id=:c"+(actor.allWarehouses()?"":" AND p.id IN(:warehouses)");
            }
            case SKU -> "";
        };
        String barcode = kind==Kind.SKU
                ? "EXISTS(SELECT 1 FROM sku_barcode b WHERE b.tenant_id=p.tenant_id AND b.sku_id=p.id AND b.barcode=:q)"
                : "FALSE";
        var matches=jdbc.query("""
                SELECT p.id,p.code,p.name,CASE WHEN p.code=:q OR %s THEN 0 WHEN p.name=:q THEN 1 ELSE 2 END priority
                FROM %s p WHERE p.tenant_id=:t %s
                  AND (p.code=:q OR %s OR LOCATE(:q,p.code)>0 OR LOCATE(:q,p.name)>0)
                ORDER BY priority,p.code,p.id LIMIT %d
                """.formatted(barcode,kind.table,clause,barcode,MAX_CHOICES+1),args,
                (row,n)->new Match(row.getLong("id"),row.getString("code"),row.getString("name"),row.getInt("priority")));
        var candidates=new ArrayList<Match>();
        for(Match match:matches) {
            if(!candidates.isEmpty()&&candidates.getFirst().priority()!=match.priority()) break;
            if(visible.test(match)) candidates.add(match);
        }
        if(candidates.isEmpty()) throw missing();
        if (candidates.size()>1) throw new Ambiguous(candidates);
        // 候选未完整扫描时，不将单个可访问的模糊结果误认为唯一匹配。
        if (candidates.getFirst().priority()==matches.getLast().priority()&&matches.size()>MAX_CHOICES)
            throw AiBusinessInput.invalid("匹配范围过大，请提供更完整的编码或名称");
        return candidates.getFirst();
    }

    static ApiException missing() {
        return new ApiException(404,"NOT_FOUND","查询对象不存在或无权查看");
    }
}
