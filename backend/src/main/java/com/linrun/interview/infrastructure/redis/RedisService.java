package com.linrun.interview.infrastructure.redis;

import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.AutoClaimResult;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Redis 服务封装
 * 提供通用的 Redis 操作，包括缓存、分布式锁、Stream 消息队列等
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedissonClient redissonClient;

    // ==================== 基础键值操作 ====================

    /**
     * 设置值（无过期时间）
     */
    public <T> void set(String key, T value) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value);
    }

    /**
     * 设置值（带过期时间）
     */
    public <T> void set(String key, T value, Duration ttl) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        bucket.set(value, ttl);
    }

    /**
     * 获取值
     */
    public <T> T get(String key) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        return bucket.get();
    }

    /**
     * 获取值，如果不存在则使用 loader 加载并缓存
     */
    public <T> T getOrLoad(String key, Duration ttl, Function<String, T> loader) {
        RBucket<T> bucket = redissonClient.getBucket(key);
        T value = bucket.get();
        if (value == null) {
            value = loader.apply(key);
            if (value != null) {
                bucket.set(value, ttl);
            }
        }
        return value;
    }

    /**
     * 删除键
     */
    public boolean delete(String key) {
        return redissonClient.getBucket(key).delete();
    }

    /**
     * 检查键是否存在
     */
    public boolean exists(String key) {
        return redissonClient.getBucket(key).isExists();
    }

    /**
     * 设置过期时间
     */
    public boolean expire(String key, Duration ttl) {
        return redissonClient.getBucket(key).expire(ttl);
    }

    /**
     * 获取剩余过期时间（毫秒）
     */
    public long getTimeToLive(String key) {
        return redissonClient.getBucket(key).remainTimeToLive();
    }

    // ==================== Hash 操作 ====================

    /**
     * 设置 Hash 字段
     */
    public <K, V> void hSet(String key, K field, V value) {
        RMap<K, V> map = redissonClient.getMap(key);
        map.put(field, value);
    }

    /**
     * 获取 Hash 字段
     */
    public <K, V> V hGet(String key, K field) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.get(field);
    }

    /**
     * 获取整个 Hash
     */
    public <K, V> Map<K, V> hGetAll(String key) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.readAllMap();
    }

    /**
     * 删除 Hash 字段
     */
    public <K, V> boolean hDelete(String key, K field) {
        RMap<K, V> map = redissonClient.getMap(key);
        return map.remove(field) != null;
    }

    /**
     * 检查 Hash 字段是否存在
     */
    public <K> boolean hExists(String key, K field) {
        RMap<K, Object> map = redissonClient.getMap(key);
        return map.containsKey(field);
    }

    // ==================== 分布式锁 ====================

    /**
     * 获取锁（阻塞等待）
     */
    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    /**
     * 尝试获取锁（非阻塞）
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放锁
     */
    public void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /**
     * 执行带锁的操作
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                  TimeUnit unit, LockedOperation<T> operation) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(waitTime, leaseTime, unit)) {
                try {
                    return operation.execute();
                } finally {
                    lock.unlock();
                }
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁失败: " + lockKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取锁被中断: " + lockKey, e);
        }
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T execute();
    }

    // ==================== Stream 消息队列 ====================

    /**
     * Stream 消息处理器接口
     */
    @FunctionalInterface
    public interface StreamMessageProcessor {
        void process(StreamMessageId messageId, Map<String, String> data);
    }

    /**
     * 消费 Stream 消息（阻塞模式）
     * 使用 Redis BLOCK 参数，让服务端等待消息，比客户端轮询更高效
     *
     * @param streamKey      Stream 键
     * @param groupName      消费者组名
     * @param consumerName   消费者名
     * @param count          每次读取数量
     * @param blockTimeoutMs 阻塞等待超时时间（毫秒），0 表示无限等待
     * @param processor      消息处理器
     * @return true 如果处理了消息，false 如果超时无消息
     */
    public boolean streamConsumeMessages(
            String streamKey,
            String groupName,
            String consumerName,
            int count,
            long blockTimeoutMs,
            StreamMessageProcessor processor) {

        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);

        // 使用阻塞读取，让 Redis 服务端等待消息
        Map<StreamMessageId, Map<String, String>> messages;
        try {
            messages = stream.readGroup(
                groupName,
                consumerName,
                StreamReadGroupArgs.neverDelivered()
                    .count(count)
                    .timeout(Duration.ofMillis(blockTimeoutMs))
            );
        } catch (ClassCastException e) {
            // Redisson 4.0.0 bug: 无消息时返回 EmptyList 而非空 Map，内部强转失败。
            // 等价于"本次无消息"，静默返回即可。
            return false;
        }

        if (messages == null || messages.isEmpty()) {
            return false;
        }

        for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            processor.process(entry.getKey(), entry.getValue());
        }

        return true;
    }

    /**
     * 创建消费者组（如果不存在）
     */
    public void createStreamGroup(String streamKey, String groupName) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        try {
            stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream());
            log.info("创建 Stream 消费者组: stream={}, group={}", streamKey, groupName);
        } catch (Exception e) {
            // 组已存在，忽略
            if (e instanceof org.redisson.client.RedisException
                    && e.getMessage() != null
                    && e.getMessage().contains("BUSYGROUP")) {
                return;
            }
            log.warn("创建消费者组失败: stream={}, group={}, error={}",
                streamKey, groupName, e.getMessage(), e);
        }
    }

    /**
     * 发送消息到 Stream
     */
    public String streamAdd(String streamKey, Map<String, String> message) {
        return streamAdd(streamKey, message, 0);
    }

    /**
     * 发送消息到 Stream（带长度限制）
     *
     * @param streamKey Stream 键
     * @param message   消息内容
     * @param maxLen    最大长度，超过时自动裁剪旧消息，0 表示不限制
     * @return 消息ID
     */
    public String streamAdd(String streamKey, Map<String, String> message, int maxLen) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        StreamAddArgs<String, String> args = StreamAddArgs.entries(message);
        if (maxLen > 0) {
            args.trimNonStrict().maxLen(maxLen);
        }
        StreamMessageId messageId = stream.add(args);
        log.debug("发送 Stream 消息: stream={}, messageId={}, maxLen={}", streamKey, messageId, maxLen);
        return messageId.toString();
    }

    /**
     * 从 Stream 读取消息（消费者组模式）
     */
    public Map<StreamMessageId, Map<String, String>> streamReadGroup(
            String streamKey, String groupName, String consumerName, int count) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        return stream.readGroup(groupName, consumerName,
            StreamReadGroupArgs.neverDelivered().count(count));
    }

    /**
     * 确认消息已处理
     */
    public void streamAck(String streamKey, String groupName, StreamMessageId... ids) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        stream.ack(groupName, ids);
    }

    /**
     * 获取 Stream 长度
     */
    public long streamLen(String streamKey) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        return stream.size();
    }

    /**
     * 获取某消费者组的待确认消息数（PEL 总量）。
     * 待确认数持续偏高，意味着消息被读取后迟迟没有 ACK，是消费滞后或处理失败的早期信号。
     * 流或组尚未创建时返回 0。
     */
    public long streamPendingCount(String streamKey, String groupName) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        try {
            return stream.getPendingInfo(groupName).getTotal();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 认领空闲超时的待确认消息（XAUTOCLAIM）。
     * <p>
     * 消费者崩溃后，它已读取但未 ACK 的消息会一直滞留在消费者组的待确认列表（PEL）里。
     * 本方法把空闲时间超过 {@code minIdleTime} 的这类消息转移给当前消费者，使其能够被重新处理，
     * 避免消息因原消费者下线而永久卡住。
     *
     * @param streamKey    Stream 键
     * @param groupName    消费者组名
     * @param consumerName 认领后归属的消费者名（当前存活消费者）
     * @param minIdleTime  最小空闲时间，只认领空闲超过该时长的消息
     * @param count        单次认领上限
     * @return 被认领的消息（messageId -> 字段表），无则返回空 Map
     */
    public Map<StreamMessageId, Map<String, String>> streamAutoClaim(
            String streamKey,
            String groupName,
            String consumerName,
            Duration minIdleTime,
            int count) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        try {
            AutoClaimResult<String, String> result = stream.autoClaim(
                groupName,
                consumerName,
                minIdleTime.toMillis(),
                TimeUnit.MILLISECONDS,
                StreamMessageId.MIN,
                count
            );
            Map<StreamMessageId, Map<String, String>> messages = result.getMessages();
            return messages != null ? messages : Map.of();
        } catch (Exception e) {
            // 组/流尚未创建或 Redis 临时异常时，认领是尽力而为的补偿动作，失败不应中断主消费循环。
            log.warn("autoClaim 失败: stream={}, group={}, error={}",
                streamKey, groupName, e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * 投递消息到死信队列（一个独立的 Stream）。
     * 重试耗尽的消息转入此处，保留原始字段与失败原因，便于事后排查或重放。
     */
    public void streamAddDlq(String dlqStreamKey, Map<String, String> message) {
        try {
            streamAdd(dlqStreamKey, message, AsyncTaskStreamConstants.STREAM_MAX_LEN);
        } catch (Exception e) {
            log.error("写入死信队列失败: dlq={}, error={}", dlqStreamKey, e.getMessage(), e);
        }
    }

    /**
     * 幂等去重：尝试将任务标记为"处理中"。
     * <p>
     * 基于 {@code setIfAbsent} 的占位语义：只有首次设置成功的消费者才真正执行业务。
     * 若键已存在且为 DONE，说明该任务已完成，应直接跳过；若为 PROCESSING，说明有其他消费者
     * 正在处理或上一次处理中途崩溃（PROCESSING 带较短 TTL，过期后可被重新占用）。
     *
     * @return true 表示占位成功（本消费者应执行业务）；false 表示已被占位或已完成（应跳过）
     */
    public boolean tryAcquireIdempotency(String dedupKey, Duration processingTtl) {
        RBucket<String> bucket = redissonClient.getBucket(dedupKey, StringCodec.INSTANCE);
        return bucket.setIfAbsent(AsyncTaskStreamConstants.DEDUP_STATE_PROCESSING, processingTtl);
    }

    /**
     * 查询幂等键当前状态，可能为 PROCESSING / DONE / null（不存在）。
     */
    public String getIdempotencyState(String dedupKey) {
        RBucket<String> bucket = redissonClient.getBucket(dedupKey, StringCodec.INSTANCE);
        return bucket.get();
    }

    /**
     * 标记任务已完成：写入 DONE 并附带较长 TTL，使后续重复投递可被快速识别并跳过。
     */
    public void markIdempotencyDone(String dedupKey, Duration doneTtl) {
        RBucket<String> bucket = redissonClient.getBucket(dedupKey, StringCodec.INSTANCE);
        bucket.set(AsyncTaskStreamConstants.DEDUP_STATE_DONE, doneTtl);
    }

    /**
     * 释放处理中占位（业务失败需要重试时调用），让消息重新入队后能再次被占用执行。
     * 用 compareAndSet 原子地"当前值为 PROCESSING 才删除"，避免读取与删除之间
     * 占位过期、被其他消费者重新抢占后误删他人的占位或已完成标记。
     */
    public void releaseIdempotencyIfProcessing(String dedupKey) {
        RBucket<String> bucket = redissonClient.getBucket(dedupKey, StringCodec.INSTANCE);
        // update 传 null 表示匹配时删除键，Redisson 在服务端以 Lua 原子执行
        bucket.compareAndSet(AsyncTaskStreamConstants.DEDUP_STATE_PROCESSING, null);
    }

    // ==================== 原子计数器 ====================

    /**
     * 获取原子计数器
     */
    public RAtomicLong getAtomicLong(String key) {
        return redissonClient.getAtomicLong(key);
    }

    /**
     * 自增并返回
     */
    public long increment(String key) {
        return redissonClient.getAtomicLong(key).incrementAndGet();
    }

    /**
     * 自减并返回
     */
    public long decrement(String key) {
        return redissonClient.getAtomicLong(key).decrementAndGet();
    }

    // ==================== 列表操作 ====================

    /**
     * 从列表右侧添加元素
     */
    public <T> void listRightPush(String key, T value) {
        RList<T> list = redissonClient.getList(key);
        list.add(value);
    }

    /**
     * 获取列表所有元素
     */
    public <T> List<T> listGetAll(String key) {
        RList<T> list = redissonClient.getList(key);
        return list.readAll();
    }

    // ==================== 工具方法 ====================

    /**
     * 获取 RedissonClient（用于高级操作）
     */
    public RedissonClient getClient() {
        return redissonClient;
    }

    /**
     * 按模式删除键
     */
    public long deleteByPattern(String pattern) {
        RKeys keys = redissonClient.getKeys();
        return keys.deleteByPattern(pattern);
    }

    /**
     * 按模式查找键
     */
    public Iterable<String> findKeysByPattern(String pattern) {
        RKeys keys = redissonClient.getKeys();
        return keys.getKeysByPattern(pattern);
    }
}
