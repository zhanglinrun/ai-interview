package com.linrun.interview.modules.dingtalk.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.dingtalk.config.DingTalkProperties;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import com.linrun.interview.modules.knowledgebase.rag.RagReferenceMarkdownBuilder;
import com.linrun.interview.modules.knowledgebase.service.RagChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 钉钉 ↔ RAG 对话桥接：注入系统用户上下文，聚合 SSE 并格式化为 Markdown/文本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.dingtalk", name = "enabled", havingValue = "true")
public class DingTalkChatBridgeService {

  private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(120);

  private final DingTalkProperties properties;
  private final RagChatSessionService ragChatSessionService;

  public DingTalkFormattedReply chat(List<Long> knowledgeBaseIds, String question) {
    List<Long> kbIds = resolveKnowledgeBaseIds(knowledgeBaseIds);
    Long userId = requireSystemUserId();
    UserContext.setUserId(userId);
    try {
      var session = ragChatSessionService.createSession(new CreateSessionRequest(kbIds, "钉钉问答"));
      Long assistantMessageId = ragChatSessionService.prepareStreamMessage(session.id(), question);
      Flux<String> flux = ragChatSessionService.getStreamAnswer(session.id(), question, assistantMessageId);
      DingTalkStreamReplyAggregator.AggregatedReply aggregated =
          DingTalkStreamReplyAggregator.aggregate(flux, REPLY_TIMEOUT);
      DingTalkFormattedReply formatted = formatReply(aggregated);
      ragChatSessionService.completeStreamMessage(assistantMessageId, formatted.body());
      ragChatSessionService.maybeGenerateTitleAsync(session.id(), question);
      log.info("[DingTalkChatBridge] 回复完成: sessionId={}, markdown={}", session.id(), formatted.markdown());
      return formatted;
    } finally {
      UserContext.clear();
    }
  }

  public List<Long> resolveKnowledgeBaseIds(List<Long> override) {
    if (override != null && !override.isEmpty()) {
      return override;
    }
    String raw = properties.getDefaultKnowledgeBaseIds();
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(Long::parseLong)
        .collect(Collectors.toList());
  }

  private Long requireSystemUserId() {
    if (properties.getSystemUserId() == null || properties.getSystemUserId() <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "请配置 app.dingtalk.system-user-id（绑定平台内用户以访问其知识库）");
    }
    return properties.getSystemUserId();
  }

  private DingTalkFormattedReply formatReply(DingTalkStreamReplyAggregator.AggregatedReply aggregated) {
    if (aggregated.hasChoices()) {
      String markdown = RagReferenceMarkdownBuilder.buildChoiceMarkdown(
          aggregated.cardPrompt(), aggregated.choices());
      return new DingTalkFormattedReply(markdown, markdown, true);
    }
    String answer = aggregated.answer();
    if (answer.isBlank()) {
      answer = "抱歉，我暂时无法回答您的问题，请稍后再试。";
    }
    if (aggregated.hasReferences()) {
      String markdown = RagReferenceMarkdownBuilder.buildAnswerWithReferences(answer, aggregated.references());
      return new DingTalkFormattedReply(markdown, markdown, true);
    }
    return new DingTalkFormattedReply(answer, answer, false);
  }

  public record DingTalkFormattedReply(String body, String markdown, boolean useMarkdown) {
  }
}
