package com.linrun.interview.modules.dingtalk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.modules.knowledgebase.model.RagCardChoiceDTO;
import com.linrun.interview.modules.knowledgebase.model.RagSourceDTO;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 将 RAG SSE 流聚合为钉钉可发送的结构化回复（对齐 know-engine ChatBotCallbackListener.aggregateChatResult）。
 */
@Slf4j
public final class DingTalkStreamReplyAggregator {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DingTalkStreamReplyAggregator() {
  }

  public static AggregatedReply aggregate(Flux<String> flux, Duration timeout) {
    AggregatedReply result = new AggregatedReply();
    flux.doOnNext(event -> consumeEvent(event, result)).then().block(timeout);
    return result;
  }

  private static void consumeEvent(String event, AggregatedReply result) {
    if (event == null || event.isEmpty()) {
      return;
    }
    if (event.startsWith("progress:")
        || event.startsWith("rewritten:")
        || event.startsWith("route:")) {
      return;
    }
    if (event.startsWith("reference:")) {
      parseReferences(event.substring("reference:".length()), result);
      return;
    }
    if (event.startsWith("card:")) {
      result.cardPrompt = event.substring("card:".length()).trim();
      return;
    }
    if (event.startsWith("card_choice:")) {
      parseChoices(event.substring("card_choice:".length()), result);
      return;
    }
    result.answerBuilder.append(event);
  }

  private static void parseReferences(String json, AggregatedReply result) {
    try {
      List<RagSourceDTO> refs = MAPPER.readValue(json, new TypeReference<>() {});
      if (refs != null && !refs.isEmpty()) {
        result.references.addAll(refs);
      }
    } catch (Exception e) {
      log.warn("解析 reference 事件失败: {}", e.getMessage());
    }
  }

  private static void parseChoices(String json, AggregatedReply result) {
    try {
      List<RagCardChoiceDTO> choices = MAPPER.readValue(json, new TypeReference<>() {});
      if (choices != null && !choices.isEmpty()) {
        result.choices = choices;
      }
    } catch (Exception e) {
      log.warn("解析 card_choice 事件失败: {}", e.getMessage());
    }
  }

  public static final class AggregatedReply {
    private final StringBuilder answerBuilder = new StringBuilder();
    private final List<RagSourceDTO> references = new ArrayList<>();
    private String cardPrompt;
    private List<RagCardChoiceDTO> choices;

    public String answer() {
      return answerBuilder.toString().trim();
    }

    public List<RagSourceDTO> references() {
      return references;
    }

    public String cardPrompt() {
      return cardPrompt;
    }

    public List<RagCardChoiceDTO> choices() {
      return choices;
    }

    public boolean hasChoices() {
      return choices != null && !choices.isEmpty();
    }

    public boolean hasReferences() {
      return !references.isEmpty();
    }
  }
}
