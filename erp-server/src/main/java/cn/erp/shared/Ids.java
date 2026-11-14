package cn.erp.shared;

import java.time.Instant;

public final class Ids {
    private static final long EPOCH = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    private static final int SEQUENCE_BITS = 12;
    private static final int NODE_BITS = 10;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long NODE = readNode();
    private static long previous = -1L;
    private static long sequence;

    private Ids() {}

    private static long readNode() {
        long node = Long.parseLong(System.getenv().getOrDefault("ERP_NODE_ID", "0"));
        if (node < 0 || node >= (1L << NODE_BITS)) {
            throw new IllegalArgumentException("节点编号须在 0 到 1023 之间");
        }
        return node;
    }

    public static synchronized long next() {
        long now = System.currentTimeMillis();
        if (now < previous) {
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "服务器时钟正在校正，请稍后重试");
        }
        if (now == previous && sequence == SEQUENCE_MASK) {
            do {
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                now = System.currentTimeMillis();
            } while (now == previous);
            if (now < previous) {
                throw new ApiException(503, "SERVICE_UNAVAILABLE", "服务器时钟正在校正，请稍后重试");
            }
        }
        sequence = now == previous ? sequence + 1 : 0;
        previous = now;
        return ((now - EPOCH) << (NODE_BITS + SEQUENCE_BITS)) | (NODE << SEQUENCE_BITS) | sequence;
    }
}
