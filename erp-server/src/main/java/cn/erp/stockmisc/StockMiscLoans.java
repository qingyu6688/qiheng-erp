package cn.erp.stockmisc;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static cn.erp.salesfinance.FinanceSupport.*;

@Component
class StockMiscLoans {
    private final JdbcTemplate jdbc;
    private final StockMiscQueries queries;
    private final InventoryService inventory;

    StockMiscLoans(JdbcTemplate jdbc,StockMiscQueries queries,InventoryService inventory){
        this.jdbc=jdbc;this.queries=queries;this.inventory=inventory;
    }

    Map<String,Object> source(CompanyAccess actor,long loan,Long partner,LocalDate day,boolean lock){
        var head=queries.head(actor,loan,lock);
        state(head,"posted");
        if(!"loan".equals(head.get("purpose"))||!Objects.equals(partner,id(head,"partner_id")))throw invalid("请选择同一往来单位的原借出单");
        if(day.isBefore(((Date)head.get("business_date")).toLocalDate()))throw invalid("归还日期不能早于借出日期");
        return head;
    }

    Map<String,Object> line(CompanyAccess actor,long loan,long source,long sku){
        var rows=jdbc.queryForList("SELECT * FROM stock_misc_line WHERE tenant_id=? AND company_id=? AND doc_id=? AND id=? FOR SHARE",
                actor.tenantId(),actor.companyId(),loan,source);
        if(rows.isEmpty()||id(rows.getFirst(),"sku_id")!=sku)throw missing();
        return rows.getFirst();
    }

    void verifyQuantity(CompanyAccess actor,Map<String,Object> original,BigDecimal quantity,boolean lock){
        var returned=jdbc.queryForList("""
                SELECT l.base_qty FROM stock_misc_line l JOIN stock_misc_doc h
                  ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.doc_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.original_loan_line_id=? AND h.status='posted'
                """+(lock?" FOR SHARE":""),BigDecimal.class,actor.tenantId(),actor.companyId(),original.get("id"));
        var total=returned.stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        if(total.add(quantity).compareTo(decimal(original,"base_qty"))>0)
            throw new ApiException(422,"LOAN_RETURN_OVERFLOW","归还数量超过原借出行的未还数量");
    }

    StockSelection selection(Map<String,Object> original,StockSelection requested,BigDecimal quantity){
        var source=inventory.decodeSelection(original.get("stock_selection"));
        if(source==null)return requested;
        var sourceSerials=source.serials()==null?List.<String>of():source.serials();
        var serials=requested==null||requested.serials()==null?List.<String>of():requested.serials();
        if(!sourceSerials.isEmpty()&&serials.isEmpty()&&quantity.compareTo(BigDecimal.valueOf(sourceSerials.size()))==0)serials=sourceSerials;
        if(!sourceSerials.containsAll(serials))throw invalid("归还序列号必须来自原借出行");
        if(requested!=null&&(requested.batchNo()!=null||requested.productionDate()!=null||requested.expiryDate()!=null||
                requested.batchId()!=null&&!Objects.equals(requested.batchId(),source.batchId())))throw invalid("归还批次必须沿用原借出行");
        return new StockSelection(source.batchId(),null,null,null,requested==null?null:requested.locationId(),serials);
    }

    void verifySerialOrigins(CompanyAccess actor,long loan,long originalLine,long sku,StockSelection selection){
        var serials=selection==null||selection.serials()==null?List.<String>of():selection.serials();
        if(serials.isEmpty())return;
        if(serials.size()>500)throw invalid("每行最多500个序列号");
        var parameters=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),sku,loan,originalLine));
        parameters.addAll(serials);
        var available=jdbc.queryForList("""
                SELECT s.serial_no FROM inv_serial s JOIN inventory_movement m
                  ON m.tenant_id=s.tenant_id AND m.company_id=s.company_id AND m.id=s.last_movement_id
                WHERE s.tenant_id=? AND s.company_id=? AND s.sku_id=? AND m.source_id=? AND m.source_line_id=?
                  AND m.source_type='stock_misc_line' AND m.movement_type='stock_misc_out' AND m.posting_action='post'
                  AND s.status='shipped' AND s.serial_no IN (
                """+String.join(",",Collections.nCopies(serials.size(),"?"))+") ORDER BY s.id FOR UPDATE OF s",String.class,parameters.toArray());
        if(available.size()!=serials.size())throw new ApiException(422,"SERIAL_NOT_AVAILABLE","归还序列号已归还或属于其他借出单");
    }
}
