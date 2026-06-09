package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库向量化的并行与限流配置。
 * <p>
 * 文档级并行：向量化消费者用多线程同时处理多个文档，缓解批量上传时的串行排队。
 * 全局信号量：限制同时在飞的 embedding 调用数，避免批量上传一次性打爆
 * DashScope 的并发/配额，误伤其他在线请求。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.vectorize")
public class KnowledgeBaseVectorizeProperties {

    /**
     * 向量化消费者的工作线程数。
     * 默认 1 表示串行（与历史行为一致）；大于 1 时开启文档级并行。
     */
    private int parallelism = 3;

    /**
     * 工作线程队列容量。队列满时由消费线程兜底执行，形成自然背压，
     * 防止读取速度远超处理速度导致任务无限堆积。
     */
    private int queueCapacity = 100;

    /**
     * 全局 embedding 并发上限（信号量许可数）。
     * 控制同一时刻向 DashScope 发起的 embedding 批次调用数。
     */
    private int embeddingConcurrency = 3;

    /**
     * 获取信号量许可的最长等待时间（秒）。超时则放弃本次并发保护、直接执行，
     * 避免极端情况下任务被永久阻塞。
     */
    private int permitWaitSeconds = 60;

    /**
     * 单文档内分块级并行度。默认 4 表示单文档内的多个 embedding 批次并发执行；
     * 设为 1 时单文档内串行分批（历史行为）。
     * 大于 1 时，单个大文档内部的多个批次也并发提交，让全局信号量的许可被同一文档吃满，
     * 专门压低"单个大文档"这条关键路径的耗时。
     * <p>
     * 注意：分块并行的写操作会脱离 vectorizeAndStore 的方法级事务（Spring 事务线程绑定），
     * 依赖消费者失败标记 + revectorize 幂等重建保证最终一致。
     */
    private int chunkParallelism = 4;
}
