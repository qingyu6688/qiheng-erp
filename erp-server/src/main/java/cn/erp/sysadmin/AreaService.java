package cn.erp.sysadmin;

import cn.erp.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AreaService {
    private final JdbcTemplate jdbc;
    private volatile List<Map<String,Object>> tree;
    public AreaService(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Map<String,Object> tree() {
        if(tree==null) {
            synchronized(this) {
                if(tree==null) {
                    var roots=new ArrayList<Map<String,Object>>();var index=new LinkedHashMap<String,Map<String,Object>>();
                    for(var row:jdbc.queryForList("SELECT id,parent_id,name,level FROM sys_area ORDER BY id")) {var node=ApiRows.row(row);node.put("id",row.get("id").toString());index.put(node.get("id").toString(),node);}
                    for(var node:index.values()) {String parent=node.get("parentId").toString();if(parent.equals("0")) roots.add(node);else {var container=index.get(parent);if(container==null) throw new IllegalStateException("区划父级缺失，请检查迁移");children(container).add(node);}}
                    tree=List.copyOf(roots);
                }
            }
        }
        return Map.of("version","MCA-2024","items",tree);
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> children(Map<String,Object> row) {return (List<Map<String,Object>>)row.computeIfAbsent("children",key->new ArrayList<Map<String,Object>>());}
    public void validate(Long area) {if(area!=null&&(area<=0||jdbc.queryForObject("SELECT COUNT(*) FROM sys_area WHERE id=?",Long.class,area)==0)) throw new ApiException(400,"VALIDATION_FAILED","请选择有效省市区");}
}
