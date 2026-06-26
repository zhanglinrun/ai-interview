package com.linrun.interview.modules.voiceinterview.listener;

import com.linrun.interview.common.async.AbstractStreamConsumer;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.infrastructure.redis.RedisService;
import com.linrun.interview.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import com.linrun.interview.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音面试评估 Stream 消费者
 */
@Slf4j
@Component
public class VoiceEvaluateStreamConsumer extends AbstractStreamConsumer<VoiceEvaluateStreamConsumer.VoiceEvaluatePayload> {

    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;

    public VoiceEvaluateStreamConsumer(RedisService redisService,
                                       VoiceInterviewService voiceInterviewService,
                                       VoiceInterviewEvaluationService evaluationService) {
        super(redisService);
        this.voiceInterviewService = voiceInterviewService;
        this.evaluationService = evaluationService;
    }

    record VoiceEvaluatePayload(String sessionId) {}

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "voice-evaluate-consumer";
    }

    @Override
    protected VoiceEvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID);
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new VoiceEvaluatePayload(sessionId);
    }

    @Override
    protected String payloadIdentifier(VoiceEvaluatePayload payload) {
        return "voiceSessionId=" + payload.sessionId();
    }

    @Override
    protected void markProcessing(VoiceEvaluatePayload payload) {
        voiceInterviewService.updateEvaluateStatus(
                Long.parseLong(payload.sessionId()), AsyncTaskStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(VoiceEvaluatePayload payload) {
        evaluationService.generateEvaluation(Long.parseLong(payload.sessionId()));
        log.info("语音面试评估完成: sessionId={}", payload.sessionId());
    }

    @Override
    protected void markCompleted(VoiceEvaluatePayload payload) {
        voiceInterviewService.updateEvaluateStatus(
                Long.parseLong(payload.sessionId()), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(VoiceEvaluatePayload payload, String error) {
        voiceInterviewService.updateEvaluateStatus(
                Long.parseLong(payload.sessionId()), AsyncTaskStatus.FAILED, error);
    }

    @Override
    protected Map<String, String> buildRetryMessage(VoiceEvaluatePayload payload, int retryCount) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, payload.sessionId(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
        );
    }
}
