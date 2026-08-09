package com.linrun.interview.dingtalk.service;

import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.dingtalk.service.DingTalkSignatureVerifier;
import com.linrun.interview.dingtalk.config.DingTalkProperties;
import com.linrun.interview.dingtalk.model.DingTalkCallbackPayload;
import com.linrun.interview.dingtalk.model.DingTalkCallbackResult;
import com.linrun.interview.rag.model.QueryRequest;
import com.linrun.interview.rag.model.QueryResponse;
import com.linrun.interview.rag.service.KnowledgeBaseQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/** 钉钉入站编排：验签、幂等、用户映射、RAG 查询、引用摘要和机器人回复。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkCallbackService {

    private final DingTalkProperties properties;
    private final DingTalkSignatureVerifier signatureVerifier;
    private final DingTalkReplayGuard replayGuard;
    private final KnowledgeBaseQueryService queryService;
    private final DingTalkRobotService robotService;

    public void verify(String timestamp, String signature) {
        signatureVerifier.verifyCallback(timestamp, signature);
    }

    public DingTalkCallbackResult handle(DingTalkCallbackPayload payload,
                                         String timestamp, String signature) {
        signatureVerifier.verifyCallback(timestamp, signature);
        String messageId = StringUtils.hasText(payload.msgId())
            ? payload.msgId() : timestamp + ":" + payload.textContent().hashCode();
        if (!replayGuard.claim(messageId)) {
            return DingTalkCallbackResult.duplicate(messageId);
        }
        String question = normalize(payload.textContent());
        if (question.isBlank()) {
            return new DingTalkCallbackResult(messageId, "IGNORED", "", null);
        }
        Long userId = parseUserId(payload.senderStaffId());
        List<Long> knowledgeBaseIds = properties.getDefaultKnowledgeBaseIds() == null
            ? List.of() : properties.getDefaultKnowledgeBaseIds();
        if (knowledgeBaseIds.isEmpty()) {
            String message = "钉钉问答尚未配置默认知识库，请联系管理员。";
            sendReply(payload, message, userId);
            return new DingTalkCallbackResult(messageId, "NO_KNOWLEDGE_BASE", message, null);
        }

        try {
            UserContext.setUserId(userId);
            QueryResponse response = queryService.queryKnowledgeBase(
                new QueryRequest(knowledgeBaseIds, question));
            String answer = formatAnswer(response);
            sendReply(payload, answer, userId);
            return new DingTalkCallbackResult(messageId, "PROCESSED", answer, null);
        } catch (Exception ex) {
            log.error("钉钉回调 RAG 处理失败: messageId={}", messageId, ex);
            String message = "暂时无法完成检索，请稍后重试。";
            sendReply(payload, message, userId);
            return new DingTalkCallbackResult(messageId, "FAILED", message, null);
        } finally {
            UserContext.clear();
        }
    }

    private void sendReply(DingTalkCallbackPayload payload, String answer, Long userId) {
        String webhook = StringUtils.hasText(payload.sessionWebhook())
            ? payload.sessionWebhook() : properties.getRobotWebhook();
        try {
            robotService.send(webhook, properties.getRobotSecret(), answer,
                userId == null ? payload.senderStaffId() : String.valueOf(userId));
        } catch (Exception ex) {
            log.warn("钉钉回复发送失败，将由出站补偿处理: messageId={}", payload.msgId(), ex);
        }
    }

    private String normalize(String text) {
        String value = text == null ? "" : text.trim();
        int limit = Math.max(1, properties.getMaxQuestionChars());
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private Long parseUserId(String senderStaffId) {
        try {
            return StringUtils.hasText(senderStaffId) ? Long.valueOf(senderStaffId)
                : properties.getFallbackUserId();
        } catch (NumberFormatException ex) {
            return properties.getFallbackUserId();
        }
    }

    private String formatAnswer(QueryResponse response) {
        String answer = StringUtils.hasText(response.answer()) ? response.answer()
            : "没有检索到足够证据。";
        if (response.sources() == null || response.sources().isEmpty()) {
            return answer;
        }
        return answer + "\n\n参考来源：" + response.sources().stream()
            .limit(3)
            .map(source -> source.documentTitle() == null ? source.sourceName() : source.documentTitle())
            .filter(StringUtils::hasText)
            .distinct()
            .reduce((left, right) -> left + "、" + right)
            .orElse("知识库");
    }
}
