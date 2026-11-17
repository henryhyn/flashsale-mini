package flashsale.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 订单号：雪花算法 (41 位毫秒时间戳 + 10 位机器号 + 12 位序列号)。
 * 原理和时钟回拨的处理见书中 J2 一章。机器号由配置 flashsale.worker-id 指定 (默认 1)，
 * 部署多个实例时每个实例必须不同，否则同一毫秒里会生成相同的订单号。
 */
@Component
public class IdGenerator {
    private static final long EPOCH = 1735689600000L;   // 2025-01-01 00:00:00 UTC
    private static final long MAX_SEQ = (1L << 12) - 1, MAX_BACKWARD_MS = 5;
    private final long workerId;
    private long lastMillis = -1, seq;

    public IdGenerator(@Value("${flashsale.worker-id:1}") long workerId) {
        if (workerId < 0 || workerId > 1023) throw new IllegalArgumentException("机器号要在 0–1023 之间");
        this.workerId = workerId;
    }

    /** 从订单号里取出生成它的时间 (毫秒时间戳)，对账时用来判断一条订单“应该早就落库了” */
    public static long timestampOf(long id) {
        return (id >> 22) + EPOCH;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastMillis) {
            // 回拨几毫秒 (NTP 校时时并不罕见) 就等时钟追上；回拨太多就拒绝发号，免得生成重复的订单号
            if (lastMillis - now > MAX_BACKWARD_MS) throw new IllegalStateException("时钟回拨了 " + (lastMillis - now) + " ms");
            while (now < lastMillis) now = System.currentTimeMillis();
        }
        if (now == lastMillis) {
            seq = (seq + 1) & MAX_SEQ;
            if (seq == 0) while ((now = System.currentTimeMillis()) <= lastMillis) { }
        } else {
            seq = 0;
        }
        lastMillis = now;
        return ((now - EPOCH) << 22) | (workerId << 12) | seq;
    }
}
