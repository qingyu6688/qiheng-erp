package cn.erp.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class InventoryModels {
    private InventoryModels() {}

    public record Key(long warehouseId, long skuId) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int warehouse = Long.compare(warehouseId, other.warehouseId);
            return warehouse == 0 ? Long.compare(skuId, other.skuId) : warehouse;
        }
    }

    public record Source(String type, long id, long lineId) {}
    public record Posting(Key key, Source source, LocalDate businessDate, long periodId, String movementType) {}
    public record StockSelection(Long batchId,String batchNo,LocalDate productionDate,LocalDate expiryDate,
                                 Long locationId,List<String> serials) {}
    public record ReservationRequest(long orderLineId, Key key, BigDecimal quantity, Instant expiresAt) {}
    public record CostResult(BigDecimal quantity, BigDecimal unitCost, BigDecimal movementCost,
                             BigDecimal adjustment, BigDecimal remainingQuantity, BigDecimal remainingCost) {}
    public record Balance(BigDecimal onHand, BigDecimal reserved, BigDecimal blocked, BigDecimal cost) {
        public BigDecimal available() { return onHand.subtract(reserved).subtract(blocked); }
    }
}
