package cn.erp.purchasing;
import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.pricing.PricingEngine;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.purchasing.PurchaseOrderQueries.*;

@Service
@Transactional
public class RequisitionService {
    private final JdbcTemplate jdbc;private final CatalogAvailability catalog;private final BusinessCommands commands;
    private final DocumentNumbers numbers;private final AuditService audit;private final PricingEngine pricing;
    public RequisitionService(JdbcTemplate jdbc,CatalogAvailability catalog,BusinessCommands commands,DocumentNumbers numbers,AuditService audit,PricingEngine pricing) {this.jdbc=jdbc;this.catalog=catalog;this.commands=commands;this.numbers=numbers;this.audit=audit;this.pricing=pricing;}
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> create(CompanyAccess scope,String key,RequisitionModels.Input input) {
        return commands.execute(scope,"purchase_requisition.create",key,input,201,()->{
            date(input.businessDate());long id=Ids.next();String no=numbers.next(scope,"PR",YearMonth.from(input.businessDate()));
            jdbc.update("INSERT INTO purchase_requisition(id,tenant_id,company_id,requisition_no,requester_id,business_date,remark,created_by,updated_by,owner_user_id,owner_department_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,scope.tenantId(),scope.companyId(),no,scope.userId(),input.businessDate(),input.remark(),scope.userId(),scope.userId(),scope.userId(),cn.erp.shared.OrgOwnership.department(scope));
            write(scope,id,input);var result=get(scope,id);audit.record(scope,"purchase_requisition.create","purchase_requisition",id,null,result);return result;
        },(Class)Map.class);
    }
    public Map<String,Object> edit(CompanyAccess scope,long id,RequisitionModels.Input input) {
        var before=header(scope,id,true);version(input.version(),before);state(before,"draft");date(input.businessDate());
        jdbc.update("DELETE FROM purchase_requisition_line WHERE tenant_id=? AND company_id=? AND requisition_id=?",scope.tenantId(),scope.companyId(),id);
        write(scope,id,input);jdbc.update("UPDATE purchase_requisition SET business_date=?,remark=?,version=version+1,updated_by=? WHERE id=?",input.businessDate(),input.remark(),scope.userId(),id);
        var after=get(scope,id);audit.record(scope,"purchase_requisition.update","purchase_requisition",id,camel(before),after);return after;
    }
    public void delete(CompanyAccess scope,long id,long expected) {
        var rows=jdbc.queryForList("SELECT * FROM purchase_requisition WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"purchase_requisition")+" AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw missing();}var row=rows.getFirst();if(flag(row.get("is_deleted"))) {return;}version(expected,row);state(row,"draft");
        jdbc.update("UPDATE purchase_requisition SET is_deleted=1,version=version+1,updated_by=? WHERE id=?",scope.userId(),id);audit.record(scope,"purchase_requisition.delete","purchase_requisition",id,camel(row),null);
    }
    private void write(CompanyAccess scope,long id,RequisitionModels.Input input) {
        if(input.lines()==null||input.lines().isEmpty()||input.lines().size()>500) {throw invalid("请购须有1到500行");}
        int lineNo=0;
        for(var line:input.lines()) {
            long sku=identifier(line.skuId()),unit=identifier(line.unitId());catalog.requireActiveSku(scope,sku,unit);
            BigDecimal rate=jdbc.queryForObject("SELECT rate_to_base FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND unit_id=?",BigDecimal.class,scope.tenantId(),sku,unit);
            BigDecimal qty=quantity(line.qty()),base=new BigDecimal(pricing.convertQuantity(qty.toPlainString(),rate.toPlainString()));
            if(line.needDate()!=null&&line.needDate().isBefore(input.businessDate())) {throw invalid("需用日期不能早于业务日期");}
            jdbc.update("INSERT INTO purchase_requisition_line(id,tenant_id,company_id,requisition_id,line_no,sku_id,unit_id,unit_rate,qty,base_qty,need_date,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",Ids.next(),scope.tenantId(),scope.companyId(),id,++lineNo,sku,unit,rate,qty,base,line.needDate(),scope.userId(),scope.userId());
        }
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,PurchaseOrderModels.Command input) {
        return commands.execute(scope,"purchase_requisition."+action,key,List.of(id,input),200,()->{
            var before=header(scope,id,true);version(input.version(),before);String next;
            switch(action) {
                case "submit" -> {state(before,"draft");for(var row:lines(scope,id)) {catalog.requireActiveSku(scope,number(row,"sku_id"),number(row,"unit_id"));}next="pending";}
                case "withdraw" -> {state(before,"pending");next="draft";}
                case "reject" -> {state(before,"pending");reason(input.comment());next="draft";}
                case "approve" -> {state(before,"pending");if(!canApprove(scope,before)) {throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","审批人须与经办人分离");}next="approved";jdbc.update("UPDATE purchase_requisition SET approved_by=?,approved_at=UTC_TIMESTAMP(3) WHERE id=?",scope.userId(),id);}
                case "close" -> {state(before,"approved");reason(input.reason());next="closed";}
                case "cancel" -> {state(before,"approved");reason(input.reason());if(lines(scope,id).stream().anyMatch(row->decimal(row,"ordered_base_qty").signum()>0)) {throw new ApiException(422,"DEPENDENCY_EXISTS","已有采购转单，不能取消请购");}next="cancelled";}
                default -> throw invalid("请购操作无效");
            }
            jdbc.update("UPDATE purchase_requisition SET status=?,version=version+1,updated_by=? WHERE id=?",next,scope.userId(),id);
            if(Set.of("submit","approve","reject","withdraw").contains(action)) {jdbc.update("INSERT INTO approval_record(id,tenant_id,company_id,document_type,document_id,revision,action,operator_id,comment) VALUES(?,?,?,'purchase_requisition',?,0,?,?,?)",Ids.next(),scope.tenantId(),scope.companyId(),id,action,scope.userId(),input.comment()==null?input.reason():input.comment());}
            var result=get(scope,id);audit.record(scope,"purchase_requisition."+action,"purchase_requisition",id,camel(before),result);return result;
        },(Class)Map.class);
    }
    public Map<String,Object> header(CompanyAccess scope,long id,boolean lock) {
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"purchase_requisition",id);
        var rows=jdbc.queryForList("SELECT * FROM purchase_requisition WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"purchase_requisition")+" AND id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);if(rows.isEmpty()) {throw missing();}return rows.getFirst();
    }
    public List<Map<String,Object>> lines(CompanyAccess scope,long id) {return jdbc.queryForList("SELECT * FROM purchase_requisition_line WHERE tenant_id=? AND company_id=? AND requisition_id=? ORDER BY line_no",scope.tenantId(),scope.companyId(),id);}
    public Map<String,Object> get(CompanyAccess scope,long id) {
        var raw=header(scope,id,false);var result=camel(raw);result.put("documentNo",raw.get("requisition_no"));result.put("allowedActions",actions(scope,raw));
        result.put("requesterName",jdbc.queryForObject("SELECT display_name FROM sys_user WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),raw.get("requester_id")));
        result.put("lines",lines(scope,id).stream().map(line->{var row=camel(line);row.put("skuName",jdbc.queryForObject("SELECT name FROM sku WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),line.get("sku_id")));row.put("unitName",jdbc.queryForObject("SELECT name FROM unit WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),line.get("unit_id")));row.put("orderableQty",decimal(line,"base_qty").subtract(decimal(line,"ordered_base_qty")).toPlainString());return row;}).toList());return result;
    }
    public Map<String,Object> list(CompanyAccess scope,String status,Long cursor,int limit) {
        if(limit<1||limit>200||cursor!=null&&cursor<=0||status!=null&&!Set.of("draft","pending","approved","closed","cancelled").contains(status)) {throw invalid("请购查询条件无效");}
        var rows=jdbc.queryForList("SELECT id FROM purchase_requisition WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"purchase_requisition")+" AND is_deleted=0 AND id<? AND (? IS NULL OR status=?) ORDER BY id DESC LIMIT ?",scope.tenantId(),scope.companyId(),cursor==null?Long.MAX_VALUE:cursor,status,status,limit+1);
        var items=rows.stream().limit(limit).map(row->get(scope,number(row,"id"))).toList();return page(items,rows.size()>limit?items.getLast().get("id").toString():null);
    }
    public Map<String,Object> candidates(CompanyAccess scope,boolean orderable,int limit) {
        if(limit<1||limit>200) {throw invalid("候选条数无效");}
        var rows=jdbc.queryForList("SELECT l.*,h.requisition_no,s.name sku_name,u.name unit_name,l.base_qty-l.ordered_base_qty orderable_qty FROM purchase_requisition_line l JOIN purchase_requisition h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.requisition_id JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id WHERE l.tenant_id=? AND l.company_id=? AND h.is_deleted=0 AND h.status='approved' AND (?=0 OR l.base_qty>l.ordered_base_qty)"+cn.erp.shared.OrgOwnership.filter(scope,"h")+" ORDER BY h.id DESC,l.line_no LIMIT ?",scope.tenantId(),scope.companyId(),orderable,limit);
        return page(rows.stream().map(PurchaseOrderQueries::camel).toList(),null);
    }
    private List<String> actions(CompanyAccess scope,Map<String,Object> row) {
        var names=new ArrayList<String>();String state=row.get("status").toString();
        if(state.equals("draft")) {for(String n:List.of("update","delete","submit")) {add(scope,names,n);}}
        if(state.equals("pending")) {add(scope,names,"withdraw");add(scope,names,"reject");if(canApprove(scope,row)) {add(scope,names,"approve");}}
        if(state.equals("approved")) {add(scope,names,"close");if(lines(scope,number(row,"id")).stream().allMatch(l->decimal(l,"ordered_base_qty").signum()==0)) {add(scope,names,"cancel");}}
        return names;
    }
    private void add(CompanyAccess scope,List<String> names,String name) {if(scope.hasPermission("purchase_requisition:"+name)) {names.add(name);}}
    private boolean canApprove(CompanyAccess scope,Map<String,Object> row) {var policy=jdbc.queryForList("SELECT separate_order_approver FROM company_policy WHERE tenant_id=? AND company_id=?",Integer.class,scope.tenantId(),scope.companyId());return row.get("created_by")==null||number(row,"created_by")!=scope.userId()||!policy.isEmpty()&&policy.getFirst()==0;}
    static void version(Long requested,Map<String,Object> row) {if(requested==null||requested<0) {throw invalid("请提供有效版本号");}if(requested!=number(row,"version")) {throw new ApiException(409,"VERSION_CONFLICT","单据已更新，请刷新",Map.of("currentVersion",row.get("version")));}}
    static void state(Map<String,Object> row,String... values) {if(!Set.of(values).contains(row.get("status"))) {throw conflict("当前状态不允许此操作");}}
    static void reason(String value) {if(value==null||value.isBlank()||value.length()>500) {throw invalid("请填写原因或审批意见");}}
    static void date(LocalDate value) {if(value==null||value.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {throw invalid("业务日期不能晚于今天");}}
    static BigDecimal quantity(String value) {if(value==null||!value.matches("[0-9]{1,18}(?:\\.[0-9]{1,6})?")) {throw invalid("数量格式或精度无效");}var qty=new BigDecimal(value).setScale(6);if(qty.signum()<=0) {throw invalid("数量必须大于零");}return qty;}
}
