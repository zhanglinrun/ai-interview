package com.linrun.interview.modules.voiceinterview.listener;

import com.linrun.interview.common.async.AbstractStreamProducer;
import com.linrun.interview.common.async.TaskQueueChannel;
import com.linrun.interview.common.constant.AsyncTaskStreamConstants;
import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音面试评估任务生产者
 */
@Slf4j
@Component
public class VoiceEvaluateStreamProducer extends AbstractStreamProducer<String> {

    private final VoiceInterviewService voiceInterviewService;

    public VoiceEvaluateStreamProducer(TaskQueueChannel taskQueueChannel,
                                       @Lazy VoiceInterviewService voiceInterviewService) {
        super(taskQueueChannel);
        this.voiceInterviewService = voiceInterviewService;
    }

    public void sendEvaluateTask(String sessionId) {
        sendTaskInTransaction(sessionId);
    }

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(String sessionId) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(String sessionId) {
        return "voiceSessionId=" + sessionId;
    }

    @Override
    protected void onSendFailed(String sessionId, String error) {
        // 入队失败标记 PENDING（非 FAILED）：与文字面试侧一致，
        // 由 cleanupStaleSessions 的补偿扫描（COMPLETED + PENDING 超时）重派
        voiceInterviewService.updateEvaluateStatus(
                Long.parseLong(sessionId), AsyncTaskStatus.PENDING, truncateError(error));
    }
}
