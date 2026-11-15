package cn.erp.accounting;

import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SettlementService {
    private final JdbcTemplate jdbc;
    public SettlementService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public record OpenItem(String itemType,long sourceId,long partnerId,String direction,
                           BigDecimal amount,LocalDate agingDate) {
        public OpenItem {
            amount=AccountingAmounts.money(amount);
            if(itemType==null || !Set.of("receivable","payable","receipt","payment","refund_request").contains(itemType) || sourceId<=0 || partnerId<=0 || direction==null || !Set.of("D","C").contains(direction) || agingDate==null) {throw AccountingAmounts.invalid("未清项来源或方向无效");}
        }
    }

    public long open(ActorScope scope,OpenItem input) {
        validateSource(scope,input);
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,amount,open_amount,aging_date)
                VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """,id,scope.tenantId(),scope.companyId(),input.partnerId(),input.itemType(),input.sourceId(),input.direction(),input.amount(),input.amount(),input.agingDate());
        var row=jdbc.queryForMap("SELECT * FROM partner_open_item WHERE tenant_id=? AND company_id=? AND item_type=? AND source_id=? FOR UPDATE",scope.tenantId(),scope.companyId(),input.itemType(),input.sourceId());
        if(((Number)row.get("partner_id")).longValue()!=input.partnerId() || !row.get("direction").equals(input.direction()) || ((BigDecimal)row.get("amount")).compareTo(input.amount())!=0 || !((java.sql.Date)row.get("aging_date")).toLocalDate().equals(input.agingDate())) {throw new ApiException(409,"IDEMPOTENCY_KEY_REUSED","同一来源的未清项内容不一致");}
        return ((Number)row.get("id")).longValue();
    }

    public long allocate(ActorScope scope,long debitItemId,long creditItemId,BigDecimal amount,
                         String type,String triggerType,Long triggerId) {
        if(type==null || !Set.of("manual","auto_order","reversal").contains(type)) {throw AccountingAmounts.invalid("核销方式无效");}
        return allocateInternal(scope,debitItemId,creditItemId,amount,type,triggerType,triggerId,"active");
    }

    long reserve(ActorScope scope,long debitItemId,long creditItemId,BigDecimal amount,long refundId) {
        return allocateInternal(scope,debitItemId,creditItemId,amount,"refund","refund_request",refundId,"reserved");
    }

    private long allocateInternal(ActorScope scope,long debitId,long creditId,BigDecimal amount,String type,String triggerType,Long triggerId,String status) {
        amount=AccountingAmounts.money(amount);
        if(debitId==creditId || triggerType!=null && triggerType.length()>32 || triggerId!=null && triggerId<=0) {throw AccountingAmounts.invalid("核销对象无效");}
        var items=lockItems(scope,debitId,creditId);
        var debit=items.get(debitId);var credit=items.get(creditId);
        if(!"D".equals(debit.get("direction")) || !"C".equals(credit.get("direction")) || !debit.get("partner_id").equals(credit.get("partner_id")) || !debit.get("currency").equals(credit.get("currency")) || !side(scope,debit).equals(side(scope,credit))) {
            throw new ApiException(422,"SETTLEMENT_NOT_ALLOWED","核销双方须同公司、同往来、同币种、同往来侧且一借一贷");
        }
        for(long id:new TreeSet<>(List.of(debitId,creditId))) {
            if(jdbc.update("UPDATE partner_open_item SET open_amount=open_amount-?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=? AND open_amount>=?",amount,scope.tenantId(),scope.companyId(),id,amount)!=1) {throw new ApiException(422,"SETTLEMENT_AMOUNT_EXCEEDED","核销金额超过剩余额");}
        }
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO settlement_allocation(id,tenant_id,company_id,partner_id,currency,debit_item_id,
                    credit_item_id,amount,status,allocation_type,trigger_type,trigger_id,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),scope.companyId(),debit.get("partner_id"),debit.get("currency"),debitId,creditId,amount,status,type,triggerType,triggerId,scope.userId());
        return id;
    }

    public void revoke(ActorScope scope,long allocationId,String reason) {
        if(reason==null || reason.isBlank() || reason.length()>200) {throw AccountingAmounts.invalid("撤销核销须填写200字以内原因");}
        restore(scope,allocationId,"active","revoked",reason);
    }

    void release(ActorScope scope,long allocationId) {restore(scope,allocationId,"reserved","released",null);}

    private void restore(ActorScope scope,long id,String expected,String next,String reason) {
        var snapshot=jdbc.queryForList("SELECT debit_item_id,credit_item_id FROM settlement_allocation WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),id);
        if(snapshot.isEmpty()) {throw AccountingAmounts.missing();}
        lockItems(scope,((Number)snapshot.getFirst().get("debit_item_id")).longValue(),((Number)snapshot.getFirst().get("credit_item_id")).longValue());
        var row=jdbc.queryForMap("SELECT * FROM settlement_allocation WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
        if(next.equals(row.get("status"))) {return;}
        if(!expected.equals(row.get("status"))) {throw AccountingAmounts.conflict("该核销记录不能执行此操作");}
        if(next.equals("revoked") && "refund".equals(row.get("allocation_type"))) {throw AccountingAmounts.conflict("已完成退款的核销不能单独撤销，请通过退款冲销处理");}
        long debit=((Number)row.get("debit_item_id")).longValue(),credit=((Number)row.get("credit_item_id")).longValue();
        BigDecimal amount=(BigDecimal)row.get("amount");
        for(long item:new TreeSet<>(List.of(debit,credit))) {
            if(jdbc.update("UPDATE partner_open_item SET open_amount=open_amount+?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=? AND open_amount+?<=amount",amount,scope.tenantId(),scope.companyId(),item,amount)!=1) {throw AccountingAmounts.conflict("未清项余额与核销记录不一致，请先对账");}
        }
        jdbc.update("""
                UPDATE settlement_allocation SET status=?,revoked_by=?,revoked_at=?,revoke_reason=?,version=version+1 WHERE id=?
                """,next,reason==null?null:scope.userId(),reason==null?null:java.sql.Timestamp.from(java.time.Instant.now()),reason,id);
    }

    /** 已签收应收生成后调用，仅匹配该订单关联且已确认的预收。 */
    public BigDecimal autoAllocateOrder(ActorScope scope,long orderId,long debitItemId) {
        var rows=jdbc.queryForList("""
                SELECT p.id FROM partner_open_item p JOIN receipt r ON r.tenant_id=p.tenant_id AND r.company_id=p.company_id
                  AND p.item_type='receipt' AND r.id=p.source_id
                WHERE p.tenant_id=? AND p.company_id=? AND r.related_order_id=? AND r.receipt_type='customer'
                  AND r.status='confirmed' AND p.open_amount>0 ORDER BY r.business_date,r.id
                """,Long.class,scope.tenantId(),scope.companyId(),orderId);
        var ids=new ArrayList<>(rows);ids.add(debitItemId);
        lockItems(scope,ids.stream().mapToLong(Long::longValue).toArray());
        BigDecimal used=BigDecimal.ZERO.setScale(2);
        for(long credit:rows) {
            BigDecimal remaining=jdbc.queryForObject("SELECT open_amount FROM partner_open_item WHERE id=?",BigDecimal.class,debitItemId);
            BigDecimal available=jdbc.queryForObject("SELECT open_amount FROM partner_open_item WHERE id=?",BigDecimal.class,credit);
            BigDecimal value=remaining.min(available);
            if(value.signum()>0) {allocate(scope,debitItemId,credit,value,"auto_order","sales_order",orderId);used=used.add(value);}
        }
        return used;
    }

    Map<Long,Map<String,Object>> lockItems(ActorScope scope,long... ids) {
        var result=new LinkedHashMap<Long,Map<String,Object>>();
        Arrays.stream(ids).sorted().distinct().forEach(id->{
            var rows=jdbc.queryForList("SELECT * FROM partner_open_item WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
            if(rows.isEmpty()) {throw AccountingAmounts.missing();}
            result.put(id,rows.getFirst());
        });
        return result;
    }

    private String side(ActorScope scope,Map<String,Object> item) {
        String type=item.get("item_type").toString();
        if(type.equals("refund_request")) {
            String status=jdbc.queryForObject("SELECT status FROM refund_request WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),item.get("source_id"));
            if(Set.of("cancelled").contains(status)) {throw AccountingAmounts.conflict("已取消退款的额度不能用于核销");}
        }
        return switch(type) {
            case "payable","payment" -> "payable";
            case "receipt" -> {
                String receiptType=jdbc.queryForObject("SELECT receipt_type FROM receipt WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),item.get("source_id"));
                yield "supplier_refund".equals(receiptType)?"payable":"receivable";
            }
            default -> "receivable";
        };
    }

    private void validateSource(ActorScope scope,OpenItem input) {
        String partner=switch(input.itemType()) {case "receivable","refund_request" -> "customer_id";case "payable" -> "supplier_id";default -> "partner_id";};
        var rows=jdbc.queryForList("SELECT * FROM "+input.itemType()+" WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),input.sourceId());
        if(rows.isEmpty()) {throw AccountingAmounts.missing();}
        var row=rows.getFirst();
        if(!((java.sql.Date)row.get("business_date")).toLocalDate().equals(input.agingDate())) {throw AccountingAmounts.invalid("未清项账龄日期须与来源业务日期一致");}
        if(row.get(partner)==null || ((Number)row.get(partner)).longValue()!=input.partnerId() || row.containsKey("currency") && !"CNY".equals(row.get("currency")) || ((BigDecimal)row.get("amount")).compareTo(input.amount())!=0) {throw AccountingAmounts.invalid("未清项与来源单据的往来、币种或金额不符");}
        String direction=switch(input.itemType()) {
            case "receivable" -> "charge".equals(row.get("kind"))?"D":"C";
            case "payable" -> "charge".equals(row.get("kind"))?"C":"D";
            case "receipt" -> "C";
            default -> "D";
        };
        if(!input.direction().equals(direction)) {throw AccountingAmounts.invalid("未清项方向与来源不符");}
        if(Set.of("receipt","payment").contains(input.itemType()) && !"confirmed".equals(row.get("status"))) {throw AccountingAmounts.conflict("收付款确认后才能生成未清项");}
        if(input.itemType().equals("refund_request") && !Set.of("draft","pending_review").contains(row.get("status"))) {throw AccountingAmounts.conflict("退款当前状态不能新建占用未清项");}
    }
}
