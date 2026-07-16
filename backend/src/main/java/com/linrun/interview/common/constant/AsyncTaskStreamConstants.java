package com.linrun.interview.common.constant;

/**
 * RabbitMQ 异步任务通用常量。
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

    /** 数据所有者用户 ID；异步线程不得临时读取 UserContext。 */
    public static final String FIELD_USER_ID = "userId";

    /**
     * 任务唯一标识字段。生产时生成一个 UUID，重新投递时保持不变，
     * 作为幂等去重的业务键：同一 taskId 的工作只会真正执行一次。
     */
    public static final String FIELD_TASK_ID = "taskId";

    // ========== 可靠性通用配置 ==========

    /**
     * 幂等去重键前缀。完整键形如 {@code async:dedup:<group>:<taskId>}。
     */
    public static final String DEDUP_KEY_PREFIX = "async:dedup:";

    /**
     * 幂等状态：已完成（带较长 TTL，重复投递直接跳过业务）
     */
    public static final String DEDUP_STATE_DONE = "DONE";

    /**
     * 幂等"已完成"标记 TTL（毫秒）。在此期间任何重复投递都被快速识别并跳过业务。
     */
    public static final long DEDUP_DONE_TTL_MS = 24 * 60 * 60 * 1000L;

    // ========== 通用消费者配置 ==========

    /**
     * 最大重试次数
     */
    public static final int MAX_RETRY_COUNT = 3;

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
     * 面试会话ID字段
     */
    public static final String FIELD_SESSION_ID = "sessionId";

    // ========== 岗位实战准备 ==========

    public static final String JOB_INTERVIEW_PREPARE_STREAM_KEY = "job-interview:prepare:stream";
    public static final String JOB_INTERVIEW_PREPARE_GROUP_NAME = "job-interview-prepare-group";
    public static final String FIELD_PREPARATION_RUN_ID = "preparationRunId";

    // ========== 证据化复盘生成 ==========

    public static final String INTERVIEW_REPORT_STREAM_KEY = "interview:report:stream";
    public static final String INTERVIEW_REPORT_GROUP_NAME = "interview-report-group";
    public static final String FIELD_REPORT_ID = "reportId";

    // ========== RabbitMQ 拓扑 ==========
    // direct exchange，每条管道一个业务队列 + 一个死信队列（DLQ）；业务队列声明
    // x-dead-letter-exchange 指向 DLX，消费重试耗尽后由容器拒绝、经 DLX 路由进 DLQ。
    // TaskQueueChannel 的逻辑管道键（*_STREAM_KEY）映射为下面的 routing key。

    /** 业务消息主交换机（direct） */
    public static final String RABBIT_TASK_EXCHANGE = "ai.interview.task.exchange";

    /** 死信交换机（direct） */
    public static final String RABBIT_TASK_DLX = "ai.interview.task.dlx";

    /** 简历分析：队列 / routing key / 死信队列 / 死信 routing key */
    public static final String RABBIT_RESUME_ANALYZE_QUEUE = "resume.analyze.queue";
    public static final String RABBIT_RESUME_ANALYZE_ROUTING = "resume.analyze";
    public static final String RABBIT_RESUME_ANALYZE_DLQ = "resume.analyze.dlq";
    public static final String RABBIT_RESUME_ANALYZE_DLQ_ROUTING = "resume.analyze.dlq";

    /** 面试评估：队列 / routing key / 死信队列 / 死信 routing key */
    public static final String RABBIT_INTERVIEW_EVALUATE_QUEUE = "interview.evaluate.queue";
    public static final String RABBIT_INTERVIEW_EVALUATE_ROUTING = "interview.evaluate";
    public static final String RABBIT_INTERVIEW_EVALUATE_DLQ = "interview.evaluate.dlq";
    public static final String RABBIT_INTERVIEW_EVALUATE_DLQ_ROUTING = "interview.evaluate.dlq";

    /** 岗位实战准备：队列 / routing key / 死信队列 / 死信 routing key。 */
    public static final String RABBIT_JOB_INTERVIEW_PREPARE_QUEUE = "job.interview.prepare.queue";
    public static final String RABBIT_JOB_INTERVIEW_PREPARE_ROUTING = "job.interview.prepare";
    public static final String RABBIT_JOB_INTERVIEW_PREPARE_DLQ = "job.interview.prepare.dlq";
    public static final String RABBIT_JOB_INTERVIEW_PREPARE_DLQ_ROUTING = "job.interview.prepare.dlq";

    /** 证据化复盘：队列 / routing key / 死信队列 / 死信 routing key。 */
    public static final String RABBIT_INTERVIEW_REPORT_QUEUE = "interview.report.queue";
    public static final String RABBIT_INTERVIEW_REPORT_ROUTING = "interview.report";
    public static final String RABBIT_INTERVIEW_REPORT_DLQ = "interview.report.dlq";
    public static final String RABBIT_INTERVIEW_REPORT_DLQ_ROUTING = "interview.report.dlq";

}
