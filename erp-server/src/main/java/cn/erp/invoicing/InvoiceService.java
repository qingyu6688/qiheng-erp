package cn.erp.invoicing;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Transactional(readOnly=true)
public class InvoiceService {
    public record Line(@NotNull(message="请选择应收来源") Long receivableId,@NotBlank(message="请输入分配金额") String amount) {}
    public record Input(@Pattern(regexp="sales",message="本阶段登记销项发票") String invoiceType,
                        @NotNull(message="请选择客户") Long partnerId,
                        @NotBlank(message="请输入发票号") @Size(max=64,message="发票号最多64字") String invoiceNo,
                        @NotNull(message="请选择开票日期") LocalDate invoiceDate,
                        @NotBlank(message="请输入发票金额") String amount,@NotBlank(message="请输入税额") String taxAmount,
                        @NotEmpty(message="请选择至少一笔应收") @Size(max=200,message="最多关联200笔应收") List<@NotNull(message="应收来源不能为空") @Valid Line> lines,
                        @Size(max=200,message="备注最多200字") String remark) {}
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final BusinessCommands commands;
    private final SalesInvoiceLock invoiceLock;
    private final SalesInvoiceAmounts invoiceAmounts;
    public InvoiceService(JdbcTemplate jdbc,AuditService audit,BusinessCommands commands,SalesInvoiceLock invoiceLock,SalesInvoiceAmounts invoiceAmounts) {
        this.jdbc=jdbc;this.audit=audit;this.commands=commands;this.invoiceLock=invoiceLock;this.invoiceAmounts=invoiceAmounts;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> create(CompanyAccess scope,String key,Input input) {
        return commands.execute(scope,"invoice_registration.create",key,input,201,()->save(scope,input),(Class)Map.class);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public long reserveElectronic(CompanyAccess scope,long requestId,Input input) {
        var registered=save(scope,input);
        long id=Long.parseLong(registered.get("id").toString());
        jdbc.update("UPDATE invoice_registration SET einvoice_request_id=? WHERE tenant_id=? AND company_id=? AND id=?",
                requestId,scope.tenantId(),scope.companyId(),id);
        return id;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void completeElectronic(CompanyAccess scope,long registration,long requestId,String number,LocalDate date) {
        if(jdbc.update("""
                UPDATE invoice_registration SET invoice_no=?,invoice_date=?,remark='数电发票已开具',updated_by=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=? AND einvoice_request_id=? AND status='registered'
                """,number,date,scope.userId(),scope.tenantId(),scope.companyId(),registration,requestId)!=1)
            throw new ApiException(409,"STATE_CONFLICT","数电发票对应的登记已变化");
        audit.record(scope,"invoice_registration.electronic_issue","invoice_registration",registration,null,
                Map.of("requestId",Long.toString(requestId),"invoiceNo",number));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void voidElectronic(CompanyAccess scope,long registration,long requestId) {
        Long version=jdbc.queryForList("""
                SELECT version FROM invoice_registration WHERE tenant_id=? AND company_id=? AND id=? AND einvoice_request_id=?
                """,Long.class,scope.tenantId(),scope.companyId(),registration,requestId).stream().findFirst().orElseThrow(InvoiceService::missing);
        voidInvoice(scope,registration,version,"einv_void_"+requestId,requestId);
    }

    private Map<String,Object> save(CompanyAccess scope,Input input) {
        invoiceLock.lock(scope);
        BigDecimal amount=number(input.amount(),true),tax=number(input.taxAmount(),false);
        if(tax.compareTo(amount)>0 || input.invoiceDate().isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))) {throw invalid("发票金额、税额或日期无效");}
        var sorted=input.lines().stream().sorted(Comparator.comparing(Line::receivableId)).toList();
        if(sorted.stream().map(Line::receivableId).distinct().count()!=sorted.size()) {throw invalid("应收来源不能重复");}
        BigDecimal allocated=BigDecimal.ZERO,sourceTax=BigDecimal.ZERO;
        var groupRequested=new HashMap<String,BigDecimal>();
        for(var line:sorted) {
            SalesInvoiceScope.receivable(jdbc,scope,line.receivableId());
            BigDecimal part=number(line.amount(),true);
            var rows=jdbc.queryForList("SELECT customer_id,kind,amount,tax_amount,posting_action,source_type FROM receivable WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),line.receivableId());
            if(rows.isEmpty()) {throw missing();}
            var ar=rows.getFirst();
            if(!"post".equals(ar.get("posting_action"))||"document_reversal".equals(ar.get("source_type"))||!jdbc.queryForList("SELECT id FROM receivable WHERE tenant_id=? AND company_id=? AND reverses_receivable_id=? FOR SHARE",Long.class,scope.tenantId(),scope.companyId(),line.receivableId()).isEmpty()) {
                throw new ApiException(409,"STATE_CONFLICT","已冲销的应收及冲销抵销往来不能登记开票");
            }
            if(((Number)ar.get("customer_id")).longValue()!=input.partnerId() || !"charge".equals(ar.get("kind"))) {throw invalid("只能关联当前客户的正向应收");}
            var used=jdbc.queryForList("""
                    SELECT l.amount FROM invoice_registration_line l JOIN invoice_registration h
                    ON h.id=l.invoice_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id
                    WHERE l.tenant_id=? AND l.company_id=? AND l.receivable_id=? AND h.status='registered' FOR SHARE
                    """,BigDecimal.class,scope.tenantId(),scope.companyId(),line.receivableId()).stream().reduce(BigDecimal.ZERO,BigDecimal::add);
            var capacity=invoiceAmounts.capacities(scope,List.of(line.receivableId())).get(line.receivableId());
            BigDecimal total=capacity.amount();
            BigDecimal requested=groupRequested.merge(capacity.group(),part,BigDecimal::add);
            if(used.add(part).compareTo(total)>0||capacity.groupUsed().add(requested).compareTo(capacity.groupAmount())>0)
                throw new ApiException(422,"INVOICE_AMOUNT_EXCEEDED","关联金额超过退货及折让后的净开票额度，请核对原票与红字记录");
            jdbc.update("UPDATE receivable SET invoiced_amount=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",used.add(part),scope.tenantId(),scope.companyId(),line.receivableId());
            allocated=allocated.add(part);
            sourceTax=sourceTax.add(capacity.tax().multiply(used.add(part)).divide(total,2,RoundingMode.HALF_UP)
                    .subtract(capacity.tax().multiply(used).divide(total,2,RoundingMode.HALF_UP)));
        }
        if(allocated.compareTo(amount)!=0) {throw invalid("分配金额合计须等于发票金额");}
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO invoice_registration(id,tenant_id,company_id,invoice_type,partner_id,invoice_no,invoice_date,
                amount,tax_amount,tax_diff_amount,remark,created_by,updated_by,owner_user_id,owner_department_id) VALUES(?,?,?,'sales',?,?,?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),scope.companyId(),input.partnerId(),input.invoiceNo(),input.invoiceDate(),amount,tax,tax.subtract(sourceTax),input.remark(),scope.userId(),scope.userId(),scope.userId(),cn.erp.shared.OrgOwnership.department(scope));
        for(var line:sorted) {jdbc.update("INSERT INTO invoice_registration_line(id,tenant_id,company_id,invoice_id,receivable_id,amount,created_by) VALUES(?,?,?,?,?,?,?)",Ids.next(),scope.tenantId(),scope.companyId(),id,line.receivableId(),number(line.amount(),true),scope.userId());}
        var result=get(scope,id);audit.record(scope,"invoice_registration.create","invoice_registration",id,null,result);return result;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> voidInvoice(CompanyAccess scope,long id,long version,String key) {
        return voidInvoice(scope,id,version,key,null);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> voidInvoice(CompanyAccess scope,long id,long version,String key,Long electronicRequestId) {
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"invoice_registration",id);
        return commands.execute(scope,electronicRequestId==null?"invoice_registration.void":"invoice_registration.electronic_void",key,List.of(id,version),200,()->{
            var row=lockForRelease(scope,id);
            var before=get(scope,id);
            var linked=row.get("einvoice_request_id");
            if(!Objects.equals(linked==null?null:((Number)linked).longValue(),electronicRequestId)) {
                throw new ApiException(409,"ELECTRONIC_INVOICE_REQUIRES_RED","数电发票待服务商确认失败或红冲后才能作废登记");
            }
            if(((Number)row.get("version")).longValue()!=version) {throw new ApiException(409,"VERSION_CONFLICT","开票登记已更新，请刷新");}
            if(!"registered".equals(row.get("status"))) {throw new ApiException(409,"STATE_CONFLICT","当前开票登记不能作废");}
            if(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_red_registration WHERE tenant_id=? AND company_id=? AND invoice_id=? AND status='pending'",
                    Long.class,scope.tenantId(),scope.companyId(),id)>0)
                throw new ApiException(409,"INVOICE_RED_PENDING","该票有待确认红字登记，请先完成或撤回红字登记");
            release(scope,id);
            var after=get(scope,id);audit.record(scope,"invoice_registration.void","invoice_registration",id,before,after);return after;
        },(Class)Map.class);
    }

    Map<String,Object> lockForRelease(CompanyAccess scope,long id) {
        invoiceLock.lock(scope);
        SalesInvoiceScope.registration(jdbc,scope,id);
        for(long ar:jdbc.queryForList("SELECT receivable_id FROM invoice_registration_line WHERE tenant_id=? AND company_id=? AND invoice_id=? AND receivable_id IS NOT NULL ORDER BY receivable_id",
                Long.class,scope.tenantId(),scope.companyId(),id))
            jdbc.queryForList("SELECT id FROM receivable WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),ar);
        var rows=jdbc.queryForList("SELECT * FROM invoice_registration WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty())throw missing();
        return rows.getFirst();
    }

    /** 仅在调用方已锁定原票并校验状态后释放，不改写原发票或应收的货款和税额。 */
    void release(CompanyAccess scope,long id) {
        var amounts=jdbc.queryForList("SELECT receivable_id,amount FROM invoice_registration_line WHERE tenant_id=? AND company_id=? AND invoice_id=? ORDER BY receivable_id",scope.tenantId(),scope.companyId(),id);
        for(var line:amounts) {
            if(jdbc.update("UPDATE receivable SET invoiced_amount=invoiced_amount-?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=? AND invoiced_amount>=?",line.get("amount"),scope.tenantId(),scope.companyId(),line.get("receivable_id"),line.get("amount"))!=1)
                throw new ApiException(409,"STATE_CONFLICT","开票关联金额不一致，请先核对");
        }
        if(jdbc.update("UPDATE invoice_registration SET status='voided',version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=? AND status='registered'",
                scope.userId(),scope.tenantId(),scope.companyId(),id)!=1)
            throw new ApiException(409,"STATE_CONFLICT","当前开票登记不能释放");
    }

    public Map<String,Object> get(CompanyAccess scope,long id) {
        SalesInvoiceScope.registration(jdbc,scope,id);
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"invoice_registration",id);
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),id));
        var rows=jdbc.queryForList("SELECT e.* FROM invoice_registration e WHERE e.tenant_id=? AND e.company_id=? AND e.id=?"+
                OrgOwnership.filter(scope,"e")+electronicSourceFilter(scope,args,true),args.toArray());
        if(rows.isEmpty()) {throw missing();}
        var result=view(rows.getFirst());
        result.put("partnerName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),rows.getFirst().get("partner_id")));
        result.put("lines",jdbc.queryForList("SELECT id,receivable_id,amount FROM invoice_registration_line WHERE tenant_id=? AND company_id=? AND invoice_id=? ORDER BY id",scope.tenantId(),scope.companyId(),id).stream().map(InvoiceService::view).toList());
        var red=jdbc.queryForList("SELECT id,status FROM invoice_red_registration WHERE tenant_id=? AND company_id=? AND invoice_id=? AND status IN('pending','confirmed')",
                scope.tenantId(),scope.companyId(),id);
        result.put("manualRedStatus",red.isEmpty()?null:red.getFirst().get("status"));
        if(scope.hasPermission("invoice_red:view")&&!red.isEmpty())result.put("manualRedId",red.getFirst().get("id").toString());
        result.put("allowedActions","registered".equals(result.get("status"))&&result.get("einvoiceRequestId")==null
                &&red.stream().noneMatch(row->"pending".equals(row.get("status")))
                &&scope.hasPermission("invoice_registration:void")?List.of("void"):List.of());
        return result;
    }

    public Map<String,Object> list(CompanyAccess scope,Long cursor,int limit) {
        if(limit<1||limit>200||cursor!=null&&cursor<1) {throw invalid("分页参数有误");}
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),cursor==null?Long.MAX_VALUE:cursor));
        String visible=electronicSourceFilter(scope,args,true)+" AND "+SalesInvoiceScope.registrationCondition(scope,"e");
        args.add(limit+1);
        var rows=jdbc.queryForList("SELECT e.id FROM invoice_registration e WHERE e.tenant_id=? AND e.company_id=? AND e.id<?"+
                OrgOwnership.filter(scope,"e")+visible+" ORDER BY e.id DESC LIMIT ?",args.toArray());
        var items=rows.stream().limit(limit).map(row->get(scope,((Number)row.get("id")).longValue())).toList();
        var page=new LinkedHashMap<String,Object>();page.put("items",items);page.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);return page;
    }

    /** 申请和旧登记都以发货单范围裁剪数电发票，主查询表别名统一为 e。 */
    public static String electronicSourceFilter(CompanyAccess scope,List<Object> args,boolean registration) {
        if(scope.allWarehouses()&&scope.orgScope().allDepartments())return "";
        var allowed=new ArrayList<String>();
        if(!scope.allWarehouses()) {
            if(scope.warehouseIds().isEmpty())return registration?" AND e.einvoice_request_id IS NULL":" AND 1=0";
            allowed.add("h.warehouse_id IN ("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")");
            args.addAll(scope.warehouseIds().stream().sorted().toList());
        }
        if(!scope.orgScope().allDepartments())allowed.add("("+scope.orgScope().ownerFilter("h")+")");
        allowed.add(OrgOwnership.sourceCondition(scope,"invoice_source.source_type","invoice_source.source_id"));
        String request=registration?"e.einvoice_request_id":"e.id";
        return " AND NOT EXISTS("+"""
                SELECT 1 FROM einv_issue_line l JOIN shipment h
                ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.shipment_id
                JOIN receivable invoice_source ON invoice_source.tenant_id=l.tenant_id AND invoice_source.company_id=l.company_id AND invoice_source.id=l.receivable_id
                WHERE l.tenant_id=e.tenant_id AND l.company_id=e.company_id AND l.request_id="""+request+
                " AND NOT COALESCE(("+String.join(" AND ",allowed)+"),FALSE))";
    }

    private static Map<String,Object> view(Map<String,Object> raw) {
        var row=new LinkedHashMap<String,Object>();
        raw.forEach((name,value)->{
            StringBuilder field=new StringBuilder();boolean upper=false;
            for(char c:name.toCharArray()) {if(c=='_'){upper=true;}else{field.append(upper?Character.toUpperCase(c):c);upper=false;}}
            Object visible=value;
            if((name.equals("id")||name.endsWith("_id")||name.endsWith("_by"))&&value!=null){visible=value.toString();}
            if(value instanceof BigDecimal decimal){visible=decimal.toPlainString();}
            if(value instanceof java.sql.Date date){visible=date.toLocalDate().toString();}
            if(value instanceof java.time.LocalDateTime time){visible=time.toInstant(java.time.ZoneOffset.UTC).toString();}
            row.put(field.toString(),visible);
        });return row;
    }
    static BigDecimal number(String value,boolean positive) {
        if(value==null||!value.matches("[0-9]{1,22}(?:\\.[0-9]{1,2})?")) {throw invalid("金额格式或精度有误");}
        var number=new BigDecimal(value).setScale(2);
        if(positive&&number.signum()==0){throw invalid("金额必须大于零");}return number;
    }
    static ApiException invalid(String message){return new ApiException(400,"VALIDATION_FAILED",message);}
    static ApiException missing(){return new ApiException(404,"NOT_FOUND","应收或发票不存在或无权查看");}
}
