package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorizeProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库向量化 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行向量化
 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {

    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseVectorizeProperties vectorizeProperties;

    public VectorizeStreamConsumer(
        RedisService redisService,
        KnowledgeBaseVectorService vectorService,
        KnowledgeBaseRepository knowledgeBaseRepository,
        KnowledgeBaseVectorizeProperties vectorizeProperties
    ) {
        super(redisService);
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.vectorizeProperties = vectorizeProperties;
    }

    record VectorizePayload(Long kbId, String content) {}

    /**
     * 文档级并行：多个文档的向量化任务并发处理，缓解批量上传时的串行排队。
     */
    @Override
    protected int workerPoolSize() {
        return vectorizeProperties.getParallelism();
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "vectorize-consumer";
    }

    @Override
    protected VectorizePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (kbIdStr == null || content == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new VectorizePayload(Long.parseLong(kbIdStr), content);
    }

    @Override
    protected String payloadIdentifier(VectorizePayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected void markProcessing(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(VectorizePayload payload) {
        vectorService.vectorizeAndStore(payload.kbId(), payload.content());
    }

    @Override
    protected void markCompleted(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(VectorizePayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, error);
    }

    @Override
    protected Map<String, String> buildRetryMessage(VectorizePayload payload, int retryCount) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
        );
    }

    /**
     * 更新向量化状态
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setVectorStatus(status);
                kb.setVectorError(error);
                knowledgeBaseRepository.save(kb);
                log.debug("向量化状态已更新: kbId={}, status={}", kbId, status);
            });
        } catch (Exception e) {
            log.error("更新向量化状态失败: kbId={}, status={}, error={}", kbId, status, e.getMessage(), e);
        }
    }

}
