package flashsale.common;

import org.springframework.stereotype.Component;

/**
 * 订单号：雪花算法 (41 位毫秒时间戳 + 10 位机器号 + 12 位序列号)。
 * 原理和时钟回拨的处理见书中 J2 一章；这里机器号先固定为 1，部署多个实例时要改成启动时分配。
 */
@Component
public class IdGenerator {
    private static final long EPOCH = 1735689600000L;   // 2025-01-01 00:00:00 UTC
    private static final long WORKER_ID = 1, MAX_SEQ = (1L << 12) - 1;
    private long lastMillis = -1, seq;

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastMillis) throw new IllegalStateException("时钟回拨了 " + (lastMillis - now) + " ms");
        if (now == lastMillis) {
            seq = (seq + 1) & MAX_SEQ;
            if (seq == 0) while ((now = System.currentTimeMillis()) <= lastMillis) { }
        } else {
            seq = 0;
        }
        lastMillis = now;
        return ((now - EPOCH) << 22) | (WORKER_ID << 12) | seq;
    }
}
