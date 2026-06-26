package com.linrun.interview.common.constant;

/**
 * 异步任务 Redis Stream 通用常量
 * 包含简历分析、面试评估、语音面试评估三个异步任务的配置
 */
public final class AsyncTaskStreamConstants {

    private AsyncTaskStreamConstants() {
        // 私有构造函数，防止实例化
    }

    // ========== 通用消息字段 ==========

    /**
     * 重试次数字段
     */
    public static final String FIELD_RETRY_COUNT = "retryCount";

    /**
     * 文档内容字段
     */
    public static final String FIELD_CONTENT = "content";

    /**
     * 任务唯一标识字段。生产时生成一个 UUID，重新入队重试、被 autoClaim 认领后保持不变，
     * 作为幂等去重的业务键：同一 taskId 的工作只会真正执行一次。
     */
    public static final String FIELD_TASK_ID = "taskId";

    /**
     * 死信消息字段：进入死信队列时的失败原因
     */
    public static final String FIELD_DLQ_ERROR = "error";

    /**
     * 死信消息字段：进入死信队列的时间戳（epoch 毫秒）
     */
    public static final String FIELD_DLQ_FAILED_AT = "failedAt";

    /**
     * 死信消息字段：来源消费者组
     */
    public static final String FIELD_DLQ_GROUP = "sourceGroup";

    // ========== 可靠性通用配置 ==========

    /**
     * 死信队列 Stream Key 后缀。正常 Stream 重试耗尽后，消息连同失败原因转入 {@code <streamKey>:dlq}，
     * 既不丢失也不再占用待确认列表，便于事后排查或重放。
     */
    public static final String DLQ_STREAM_SUFFIX = ":dlq";

    /**
     * 幂等去重键前缀。完整键形如 {@code async:dedup:<group>:<taskId>}。
     */
    public static final String DEDUP_KEY_PREFIX = "async:dedup:";

    /**
     * 幂等状态：处理中（认领标记，带较短 TTL，持有者崩溃后自动过期可被重新认领）
     */
    public static final String DEDUP_STATE_PROCESSING = "PROCESSING";

    /**
     * 幂等状态：已完成（带较长 TTL，重复投递直接跳过业务）
     */
    public static final String DEDUP_STATE_DONE = "DONE";

    /**
     * 幂等"处理中"占位 TTL（毫秒）。需大于单条任务的最长处理时间，
     * 同时又要在持有者崩溃后能自动过期、让消息可被重新认领执行。
     */
    public static final long DEDUP_PROCESSING_TTL_MS = 10 * 60 * 1000L;

    /**
     * 幂等"已完成"标记 TTL（毫秒）。在此期间任何重复投递都被快速识别并跳过业务。
     */
    public static final long DEDUP_DONE_TTL_MS = 24 * 60 * 60 * 1000L;

    /**
     * autoClaim 认领的最小空闲时间（毫秒）。只接管空闲超过该时长的待确认消息，
     * 避免抢走其他消费者正在处理的消息。
     */
    public static final long CLAIM_MIN_IDLE_MS = 60 * 1000L;

    /**
     * autoClaim 扫描周期（毫秒）。后台线程每隔该时长尝试认领一次超时未确认消息。
     */
    public static final long CLAIM_SCAN_INTERVAL_MS = 30 * 1000L;

    /**
     * 单次 autoClaim 认领的消息数量上限
     */
    public static final int CLAIM_BATCH_SIZE = 10;

    // ========== 通用消费者配置 ==========

    /**
     * 最大重试次数
     */
    public static final int MAX_RETRY_COUNT = 3;

    /**
     * 每次拉取的消息批次大小
     */
    public static final int BATCH_SIZE = 10;

    /**
     * 消费者轮询间隔（毫秒）
     */
    public static final long POLL_INTERVAL_MS = 1000;

    /**
     * Stream 最大长度（自动裁剪旧消息，防止无限增长）
     */
    public static final int STREAM_MAX_LEN = 1000;

    // ========== 简历分析 Stream 配置 ==========

    /**
     * 简历分析 Stream Key
     */
    public static final String RESUME_ANALYZE_STREAM_KEY = "resume:analyze:stream";

    /**
     * 简历分析 Consumer Group 名称
     */
    public static final String RESUME_ANALYZE_GROUP_NAME = "analyze-group";

    /**
     * 简历分析 Consumer 名称前缀
     */
    public static final String RESUME_ANALYZE_CONSUMER_PREFIX = "analyze-consumer-";

    /**
     * 简历ID字段
     */
    public static final String FIELD_RESUME_ID = "resumeId";

    // ========== 面试评估 Stream 配置 ==========

    /**
     * 面试评估 Stream Key
     */
    public static final String INTERVIEW_EVALUATE_STREAM_KEY = "interview:evaluate:stream";

    /**
     * 面试评估 Consumer Group 名称
     */
    public static final String INTERVIEW_EVALUATE_GROUP_NAME = "evaluate-group";

    /**
     * 面试评估 Consumer 名称前缀
     */
    public static final String INTERVIEW_EVALUATE_CONSUMER_PREFIX = "evaluate-consumer-";

    /**
     * 面试会话ID字段
     */
    public static final String FIELD_SESSION_ID = "sessionId";

    // ========== 语音面试评估 Stream 配置 ==========

    /**
     * 语音面试评估 Stream Key
     */
    public static final String VOICE_EVALUATE_STREAM_KEY = "voice:evaluate:stream";

    /**
     * 语音面试评估 Consumer Group 名称
     */
    public static final String VOICE_EVALUATE_GROUP_NAME = "voice-evaluate-group";

    /**
     * 语音面试评估 Consumer 名称前缀
     */
    public static final String VOICE_EVALUATE_CONSUMER_PREFIX = "voice-evaluate-consumer-";

    /**
     * 语音面试会话ID字段
     */
    public static final String FIELD_VOICE_SESSION_ID = "voiceSessionId";
}
