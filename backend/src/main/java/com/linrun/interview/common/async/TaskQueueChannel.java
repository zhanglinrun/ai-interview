package com.linrun.interview.common.async;

import java.util.Map;

/**
 * 异步任务投递通道抽象。业务生产者只面向本接口，当前唯一实现为 RabbitMQ。
 */
public interface TaskQueueChannel {

    /**
     * 投递一条任务消息。
     *
     * @param streamKey 逻辑管道键，RabbitMQ 实现将其映射为 routing key
     * @param message   消息字段（含 taskId / retryCount 等可靠性元数据）
     * @return 引擎侧消息 ID（用于日志追踪）
     */
    String send(String streamKey, Map<String, String> message);

}
