package com.linrun.interview.common.async;

import java.util.Map;

/**
 * 异步任务投递通道抽象：屏蔽 Redis Stream 与 RocketMQ 的发送差异。
 *
 * <p>业务生产者（{@link AbstractStreamProducer} 子类）只面向本接口发消息，
 * 具体走哪个引擎由 {@code app.async.engine} 决定（两个实现互斥装配）。
 */
public interface TaskQueueChannel {

    /**
     * 投递一条任务消息。
     *
     * @param streamKey 逻辑管道键（Redis Stream key；RocketMQ 实现会映射为 topic）
     * @param message   消息字段（含 taskId / retryCount 等可靠性元数据）
     * @return 引擎侧消息 ID（用于日志追踪）
     */
    String send(String streamKey, Map<String, String> message);

    /**
     * 以事务消息语义投递（half 消息 → 本地事务确认 → commit）。
     *
     * <p>仅 RocketMQ 实现具备真事务语义；Redis Stream 无事务消息，
     * 默认退化为普通入队（调用方已遵循 DB-first 顺序，由补偿任务兜底）。
     */
    default String sendInTransaction(String streamKey, Map<String, String> message) {
        return send(streamKey, message);
    }
}
