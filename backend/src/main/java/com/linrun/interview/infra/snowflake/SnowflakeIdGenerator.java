package com.linrun.interview.infra.snowflake;

/**
 * 雪花算法 ID 生成器（用于知识库切片的 chunkId/parentChunkId/brotherChunkId）。
 *
 * <p>生成的 ID 是 64 位长整型：
 * <ul>
 *   <li>1 位符号位（始终为 0）</li>
 *   <li>41 位时间戳（毫秒级，可使用约 69 年）</li>
 *   <li>10 位工作机器 ID（0-1023）</li>
 *   <li>12 位序列号（毫秒内自增，每毫秒可生成 4096 个 ID）</li>
 * </ul>
 *
 * <p>workerId 取值顺序：环境变量/系统属性 {@code WORKER_ID} &gt; 本机 IP 低 10 位 &gt; PID 兜底。
 * 容器化多实例部署时 PID 极易相同（常见 PID=1），IP 低 10 位在同一子网内基本唯一；
 * 需要强保证时显式配置 WORKER_ID。
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
        // 1. 显式配置优先：环境变量或系统属性 WORKER_ID
        String configured = System.getProperty("WORKER_ID", System.getenv("WORKER_ID"));
        if (configured != null && !configured.isBlank()) {
            try {
                return Long.parseLong(configured.trim()) & MAX_WORKER_ID;
            } catch (NumberFormatException ignored) {
                // 配置非法则继续走自动推导
            }
        }

        // 2. 本机非回环 IPv4 低 10 位（同一子网内不同实例基本唯一）
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                        byte[] ip = address.getAddress();
                        // 低 10 位 = 最后一段 8 位 + 倒数第二段低 2 位
                        return (((ip[2] & 0b11L) << 8) | (ip[3] & 0xFFL)) & MAX_WORKER_ID;
                    }
                }
            }
        } catch (Exception ignored) {
            // 网络接口不可用时降级 PID
        }

        // 3. PID 兜底（单机多进程场景可区分，容器内可能恒为 1）
        try {
            return ProcessHandle.current().pid() & MAX_WORKER_ID;
        } catch (Exception ignored) {
            return 1L;
        }
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
