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
    public InvoiceService(JdbcTemplate jdbc,AuditService audit,BusinessCommands commands) {this.jdbc=jdbc;this.audit=audit;this.commands=commands;}

    @Transactional
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> create(CompanyAccess scope,String key,Input input) {
        return commands.execute(scope,"invoice_registration.create",key,input,201,()->save(scope,input),(Class)Map.class);
    }

    private Map<String,Object> save(CompanyAccess scope,Input input) {
        BigDecimal amount=number(input.amount(),true),tax=number(input.taxAmount(),false);
        if(tax.compareTo(amount)>0 || input.invoiceDate().isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))) {throw invalid("发票金额、税额或日期无效");}
        var sorted=input.lines().stream().sorted(Comparator.comparing(Line::receivableId)).toList();
        if(sorted.stream().map(Line::receivableId).distinct().count()!=sorted.size()) {throw invalid("应收来源不能重复");}
        BigDecimal allocated=BigDecimal.ZERO,sourceTax=BigDecimal.ZERO;
        for(var line:sorted) {
            cn.erp.shared.OrgOwnership.requireSource(jdbc,scope,"receivable",line.receivableId());
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
            BigDecimal total=(BigDecimal)ar.get("amount");
            if(used.add(part).compareTo(total)>0) {throw new ApiException(422,"INVOICE_AMOUNT_EXCEEDED","关联开票金额超过该笔应收");}
            jdbc.update("UPDATE receivable SET invoiced_amount=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",used.add(part),scope.tenantId(),scope.companyId(),line.receivableId());
            allocated=allocated.add(part);
            sourceTax=sourceTax.add(((BigDecimal)ar.get("tax_amount")).multiply(used.add(part)).divide(total,2,RoundingMode.HALF_UP)
                    .subtract(((BigDecimal)ar.get("tax_amount")).multiply(used).divide(total,2,RoundingMode.HALF_UP)));
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

    @Transactional
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> voidInvoice(CompanyAccess scope,long id,long version,String key) {
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"invoice_registration",id);
        return commands.execute(scope,"invoice_registration.void",key,List.of(id,version),200,()->{
            // 与创建共享先锁应收、再锁发票的顺序。
            var ids=jdbc.queryForList("SELECT receivable_id FROM invoice_registration_line WHERE tenant_id=? AND company_id=? AND invoice_id=? ORDER BY receivable_id",Long.class,scope.tenantId(),scope.companyId(),id);
            for(Long ar:ids) {jdbc.queryForList("SELECT id FROM receivable WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),ar);}
            var rows=jdbc.queryForList("SELECT version,status FROM invoice_registration WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
            if(rows.isEmpty()) {throw missing();}
            if(((Number)rows.getFirst().get("version")).longValue()!=version) {throw new ApiException(409,"VERSION_CONFLICT","开票登记已更新，请刷新");}
            if(!"registered".equals(rows.getFirst().get("status"))) {throw new ApiException(409,"STATE_CONFLICT","当前开票登记不能作废");}
            var before=get(scope,id);
            var amounts=jdbc.queryForList("SELECT receivable_id,amount FROM invoice_registration_line WHERE tenant_id=? AND company_id=? AND invoice_id=?",scope.tenantId(),scope.companyId(),id);
            for(var line:amounts) {
                if(jdbc.update("UPDATE receivable SET invoiced_amount=invoiced_amount-?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=? AND invoiced_amount>=?",line.get("amount"),scope.tenantId(),scope.companyId(),line.get("receivable_id"),line.get("amount"))!=1) {throw new ApiException(409,"STATE_CONFLICT","开票关联金额不一致，请先核对");}
            }
            jdbc.update("UPDATE invoice_registration SET status='voided',version=version+1,updated_by=? WHERE id=?",scope.userId(),id);
            var after=get(scope,id);audit.record(scope,"invoice_registration.void","invoice_registration",id,before,after);return after;
        },(Class)Map.class);
    }

    public Map<String,Object> get(CompanyAccess scope,long id) {
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"invoice_registration",id);
        var rows=jdbc.queryForList("SELECT * FROM invoice_registration WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"invoice_registration")+" AND id=?",scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw missing();}
        var result=view(rows.getFirst());
        result.put("partnerName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),rows.getFirst().get("partner_id")));
        result.put("lines",jdbc.queryForList("SELECT id,receivable_id,amount FROM invoice_registration_line WHERE tenant_id=? AND company_id=? AND invoice_id=? ORDER BY id",scope.tenantId(),scope.companyId(),id).stream().map(InvoiceService::view).toList());
        result.put("allowedActions","registered".equals(result.get("status"))&&scope.hasPermission("invoice_registration:void")?List.of("void"):List.of());
        return result;
    }

    public Map<String,Object> list(CompanyAccess scope,Long cursor,int limit) {
        if(limit<1||limit>200||cursor!=null&&cursor<1) {throw invalid("分页参数有误");}
        var rows=jdbc.queryForList("SELECT id FROM invoice_registration WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"invoice_registration")+" AND id<? ORDER BY id DESC LIMIT ?",scope.tenantId(),scope.companyId(),cursor==null?Long.MAX_VALUE:cursor,limit+1);
        var items=rows.stream().limit(limit).map(row->get(scope,((Number)row.get("id")).longValue())).toList();
        var page=new LinkedHashMap<String,Object>();page.put("items",items);page.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);return page;
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
