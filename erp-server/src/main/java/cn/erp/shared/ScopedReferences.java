package cn.erp.shared;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 单次详情或列表内批量读取引用，不跨请求缓存资料和权限。 */
public final class ScopedReferences {
    private ScopedReferences() {}

    public static Map<Long,Map<String,Object>> load(JdbcTemplate jdbc,ActorScope scope,String table,Collection<Long> ids) {
        if(!Set.of("business_partner","sku","unit","warehouse").contains(table)) {throw new IllegalArgumentException("引用资料类型无效");}
        var unique=ids.stream().filter(Objects::nonNull).distinct().toList();
        if(unique.isEmpty()) {return Map.of();}
        String columns=table.equals("unit")?"id,name":"id,code,name";
        var args=new ArrayList<Object>();args.add(scope.tenantId());args.addAll(unique);
        String filter="tenant_id=? AND id IN ("+String.join(",",Collections.nCopies(unique.size(),"?"))+")";
        if(table.equals("warehouse")) {filter+=" AND company_id=?";args.add(scope.companyId());}
        var result=new HashMap<Long,Map<String,Object>>();
        for(var row:jdbc.queryForList("SELECT "+columns+" FROM "+table+" WHERE "+filter,args.toArray())) {
            long id=((Number)row.get("id")).longValue();
            row.put("id",Long.toString(id));result.put(id,row);
        }
        return result;
    }

    public static Map<String,Object> get(Map<Long,Map<String,Object>> rows,long id) {
        return rows.getOrDefault(id,Map.of("id",Long.toString(id)));
    }
}
