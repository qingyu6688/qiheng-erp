package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.inventory.InventoryStore.decimal;

/** M6 调拨用例在保存实际入库或损失结果的同一事务内调用，避免按剩余数量重新取整。 */
@Service
@Transactional
public class TransferCosts {
    public record Result(String lineId,long version,BigDecimal receivedQuantity,BigDecimal differenceQuantity,
                         BigDecimal receivedCost,BigDecimal differenceCost,BigDecimal remainingQuantity,BigDecimal remainingCost) {}
    private final JdbcTemplate jdbc;

    public TransferCosts(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    /** 实际入库成本从该笔库存入库结果取得，最后一笔包含尚未分配的分币尾差。 */
    public Result receive(CompanyAccess scope,long lineId,long expectedVersion,BigDecimal quantity,BigDecimal actualCost) {
        InventoryNumbers.quantity(quantity);InventoryNumbers.money(actualCost);
        var before=lock(scope,lineId);
        version(before,expectedVersion);
        BigDecimal received=decimal(before,"received_base_qty").add(quantity);
        BigDecimal differences=decimal(before,"diff_base_qty");
        BigDecimal receivedCost=decimal(before,"received_cost_amount").add(actualCost);
        BigDecimal differenceCost=decimal(before,"diff_cost_amount");
        validate(before,received,differences,receivedCost,differenceCost,before.get("diff_status").equals("resolved"));
        jdbc.update("""
                UPDATE stock_transfer_line SET received_base_qty=?,received_cost_amount=?,version=version+1,updated_by=?
                WHERE tenant_id=? AND company_id=? AND id=? AND version=?
                """,received,receivedCost,scope.userId(),scope.tenantId(),scope.companyId(),lineId,expectedVersion);
        return result(before,lineId,expectedVersion+1,received,differences,receivedCost,differenceCost,
                "resolved".equals(before.get("diff_status")));
    }

    /** 一次处理当前整笔待处理差异，或追加一笔实际损失。原请求的版本号只能生效一次。 */
    public Result resolveDifference(CompanyAccess scope,long lineId,long expectedVersion,BigDecimal quantity,BigDecimal actualCost) {
        InventoryNumbers.quantity(quantity);InventoryNumbers.money(actualCost);
        var before=lock(scope,lineId);
        version(before,expectedVersion);
        BigDecimal differences=decimal(before,"diff_base_qty");
        if("pending".equals(before.get("diff_status"))) {
            if(quantity.compareTo(differences)!=0) {
                throw invalid("请一次处理该行的全部待处理差异");
            }
        } else {
            differences=differences.add(quantity);
        }
        BigDecimal received=decimal(before,"received_base_qty");
        BigDecimal receivedCost=decimal(before,"received_cost_amount");
        BigDecimal differenceCost=decimal(before,"diff_cost_amount").add(actualCost);
        validate(before,received,differences,receivedCost,differenceCost,true);
        jdbc.update("""
                UPDATE stock_transfer_line SET diff_base_qty=?,diff_status='resolved',diff_cost_amount=?,version=version+1,updated_by=?
                WHERE tenant_id=? AND company_id=? AND id=? AND version=?
                """,differences,differenceCost,scope.userId(),scope.tenantId(),scope.companyId(),lineId,expectedVersion);
        return result(before,lineId,expectedVersion+1,received,differences,receivedCost,differenceCost,true);
    }

    /** M6 在库存入库前读取本笔可使用的精确剩余成本，保持同一外层事务和锁。 */
    public Result remaining(CompanyAccess scope,long lineId) {
        var row=lock(scope,lineId);
        return result(row,lineId,((Number)row.get("version")).longValue(),decimal(row,"received_base_qty"),
                decimal(row,"diff_base_qty"),decimal(row,"received_cost_amount"),decimal(row,"diff_cost_amount"),
                "resolved".equals(row.get("diff_status")));
    }

    private Map<String,Object> lock(CompanyAccess scope,long lineId) {
        var ids=jdbc.queryForList("SELECT transfer_id FROM stock_transfer_line WHERE tenant_id=? AND company_id=? AND id=?",
                Long.class,scope.tenantId(),scope.companyId(),lineId);
        if(ids.isEmpty()) {throw missing();}
        var headers=jdbc.queryForList("SELECT status,to_warehouse_id FROM stock_transfer WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",
                scope.tenantId(),scope.companyId(),ids.getFirst());
        if(headers.isEmpty() || !scope.allowsWarehouse(((Number)headers.getFirst().get("to_warehouse_id")).longValue())) {throw missing();}
        String state=headers.getFirst().get("status").toString();
        if(!state.equals("shipped") && !state.equals("received_with_diff")) {
            throw new ApiException(409,"STATE_CONFLICT","调拨单当前状态不允许接收入库或处理差异");
        }
        var rows=jdbc.queryForList("SELECT * FROM stock_transfer_line WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",
                scope.tenantId(),scope.companyId(),lineId);
        if(rows.isEmpty()) {throw missing();}
        if(rows.getFirst().get("cost_amount")==null) {
            throw new ApiException(409,"STATE_CONFLICT","调拨行尚未形成调出成本");
        }
        return rows.getFirst();
    }

    private void version(Map<String,Object> row,long requested) {
        if(requested<0) {throw invalid("版本号不能为负数");}
        long actual=((Number)row.get("version")).longValue();
        if(requested!=actual) {
            throw new ApiException(409,"VERSION_CONFLICT","调拨行已被其他操作修改，请刷新",
                    Map.of("currentVersion",actual));
        }
    }

    private void validate(Map<String,Object> before,BigDecimal received,BigDecimal difference,
                          BigDecimal receivedCost,BigDecimal differenceCost,boolean resolved) {
        BigDecimal totalQty=decimal(before,"base_qty"),totalCost=decimal(before,"cost_amount");
        if(received.add(difference).compareTo(totalQty)>0 || receivedCost.add(differenceCost).compareTo(totalCost)>0) {
            throw new ApiException(422,"RECEIVE_QTY_EXCEEDED","调拨累计数量或成本超过原调出范围");
        }
        boolean quantitiesComplete=received.add(resolved?difference:BigDecimal.ZERO).compareTo(totalQty)==0;
        if(quantitiesComplete && receivedCost.add(differenceCost).compareTo(totalCost)!=0) {
            throw new ApiException(422,"STATE_CONFLICT","最后一笔接收或差异处理须包含剩余成本尾差");
        }
    }

    private Result result(Map<String,Object> before,long id,long version,BigDecimal received,BigDecimal difference,
                          BigDecimal receivedCost,BigDecimal differenceCost,boolean resolved) {
        BigDecimal resolvedQuantity=resolved?difference:BigDecimal.ZERO;
        return new Result(Long.toString(id),version,received,difference,receivedCost,differenceCost,
                decimal(before,"base_qty").subtract(received).subtract(resolvedQuantity),
                decimal(before,"cost_amount").subtract(receivedCost).subtract(differenceCost));
    }

    private static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
    private static ApiException missing() {return new ApiException(404,"NOT_FOUND","调拨行不存在或无权查看");}
}
