package cn.erp.gl;

import java.util.*;

import org.springframework.stereotype.Component;

import cn.erp.shared.ActorScope;

import static cn.erp.gl.GlSupport.*;

/** 批量解析辅助名称，历史停用项目仍按当前公司及企业边界读取。 */
@Component
class GlDimensionViews {
    private final GlSupport s;
    private final GlDimensions dimensions;

    GlDimensionViews(GlSupport s, GlDimensions dimensions) {
        this.s = s;
        this.dimensions = dimensions;
    }

    Map<String, Map<Long, String>> load(ActorScope actor, Collection<GlModels.Aux> values) {
        var result = new LinkedHashMap<String, Map<Long, String>>();
        for (int i = 0; i < AUX.size(); i++) {
            String type = AUX.get(i);
            var ids = new TreeSet<Long>();
            for (var value : values) if (value.values().get(i) > 0) ids.add(value.values().get(i));
            var names = new HashMap<Long, String>();
            result.put(type, names);
            if (ids.isEmpty()) continue;
            String table =
                    switch (type) {
                        case "customer", "supplier" -> "business_partner";
                        case "dept" -> "org_department";
                        case "employee" -> "sys_user";
                        case "sku" -> "sku";
                        default -> "gl_aux_item";
                    };
            var args = new ArrayList<Object>(List.of(actor.tenantId()));
            boolean company = Set.of("dept", "project", "custom").contains(type);
            if (company) args.add(actor.companyId());
            args.addAll(ids);
            String fields =
                    type.equals("employee") ? "id,username code,display_name name" : "id,code,name";
            for (var row :
                    s.jdbc.queryForList(
                            "SELECT "
                                    + fields
                                    + " FROM "
                                    + table
                                    + " WHERE tenant_id=?"
                                    + (company ? " AND company_id=?" : "")
                                    + " AND id IN("
                                    + String.join(",", Collections.nCopies(ids.size(), "?"))
                                    + ")",
                            args.toArray()))
                names.put(number(row.get("id")), row.get("code") + " " + row.get("name"));
        }
        return result;
    }

    String text(GlModels.Aux value, Map<String, Map<Long, String>> names) {
        var parts = new ArrayList<String>();
        for (int i = 0; i < AUX.size(); i++) {
            long id = value.values().get(i);
            if (id > 0) {
                String type = AUX.get(i);
                parts.add(
                        dimensions.label(type)
                                + "："
                                + names.getOrDefault(type, Map.of())
                                        .getOrDefault(id, "历史项目（" + id + "）"));
            }
        }
        return String.join("；", parts);
    }
}
