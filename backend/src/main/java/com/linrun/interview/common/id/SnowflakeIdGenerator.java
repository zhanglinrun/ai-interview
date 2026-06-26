package com.linrun.interview.common.id;

/**
 * 雪花算法 ID 生成器（copy 自 know-engine，用于知识库切片的 chunkId/parentChunkId/brotherChunkId）。
 *
 * <p>生成的 ID 是 64 位长整型：
 * <ul>
 *   <li>1 位符号位（始终为 0）</li>
 *   <li>41 位时间戳（毫秒级，可使用约 69 年）</li>
 *   <li>10 位工作机器 ID（0-1023）</li>
 *   <li>12 位序列号（毫秒内自增，每毫秒可生成 4096 个 ID）</li>
 * </ul>
 */
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704038400000L;
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    private static volatile SnowflakeIdGenerator instance;

    private SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                String.format("workerId must be between 0 and %d", MAX_WORKER_ID));
        }
        this.workerId = workerId;
    }

    public static SnowflakeIdGenerator getInstance() {
        if (instance == null) {
            synchronized (SnowflakeIdGenerator.class) {
                if (instance == null) {
                    instance = new SnowflakeIdGenerator(getWorkerId());
                }
            }
        }
        return instance;
    }

    private static long getWorkerId() {
        try {
            String processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            if (processName != null && processName.contains("@")) {
                String pid = processName.split("@")[0];
                return Long.parseLong(pid) & MAX_WORKER_ID;
            }
        } catch (Exception ignored) {
            // 忽略异常，使用默认值
        }
        return 1L;
    }

    /**
     * 生成下一个 ID（线程安全）。
     *
     * @return 唯一 ID
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            throw new IllegalStateException(
                String.format("Clock moved backwards. Refusing to generate id for %d milliseconds",
                    lastTimestamp - timestamp));
        }

        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
            | (workerId << WORKER_ID_SHIFT)
            | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    /**
     * 生成下一个 ID（字符串形式）。
     *
     * @return 唯一 ID 字符串
     */
    public String nextIdStr() {
        return String.valueOf(nextId());
    }
}
