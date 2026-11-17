package cn.erp.identity;

import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/org/business-users")
class BusinessUserController {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    BusinessUserController(JdbcTemplate jdbc,AccessService access) {this.jdbc=jdbc;this.access=access;}
    @GetMapping
    ApiResponse<?> list(HttpServletRequest request,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        var actor=access.requireCompany(request,null);
        if(actor.permissions().stream().noneMatch(p->p.startsWith("sales_order:")||p.startsWith("purchase_order:")||p.startsWith("shipment:")||p.startsWith("receipt:")||p.startsWith("payment:")||p.startsWith("sales_return:")||p.startsWith("goods_receipt:")||p.startsWith("purchase_return:")||p.startsWith("report:")||p.equals("user:view"))) throw new ApiException(403,"FORBIDDEN","没有查看业务员候选的权限");
        if(limit<1||limit>200||keyword.length()>100||cursor!=null&&cursor<=0) throw new ApiException(400,"VALIDATION_FAILED","业务员筛选条件无效");
        var rows=jdbc.queryForList("SELECT CAST(u.id AS CHAR) id,u.display_name name FROM sys_user u WHERE u.tenant_id=? AND u.id<? AND u.status='active' AND (LOCATE(?,u.username)>0 OR LOCATE(?,u.display_name)>0) AND EXISTS(SELECT 1 FROM org_user_data_scope s WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=?) ORDER BY u.id DESC LIMIT ?",actor.tenantId(),cursor==null?Long.MAX_VALUE:cursor,keyword,keyword,actor.companyId(),limit+1);
        var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().limit(limit).toList());result.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id"):null);return ApiResponse.success(result);
    }
}
