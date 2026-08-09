package com.linrun.interview.chat.controller;import com.linrun.interview.chat.dto.RagChatDTO;

import com.linrun.interview.chat.dto.RagChatDTO.SendMessageRequest;
import com.linrun.interview.chat.service.RagChatSessionService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RAG 聊天流式控制器测试")
class RagChatControllerTest {

  private final RagChatSessionService sessionService = mock(RagChatSessionService.class);
  private final RagChatController controller = new RagChatController(sessionService);

  @Test
  @DisplayName("仅收到元数据时不应把助手消息落成空白")
  void shouldPersistFallbackWhenStreamContainsOnlyMetadata() {
    when(sessionService.prepareStreamMessage(1L, "项目深挖")).thenReturn(8L);
    when(sessionService.getStreamAnswer(1L, "项目深挖", 8L))
        .thenReturn(Flux.just("progress:正在生成回答..."));

    List<ServerSentEvent<String>> events = controller.sendMessageStream(
        1L, new SendMessageRequest("项目深挖")).collectList().block();

    assertThat(events).extracting(ServerSentEvent::data)
        .containsExactly("progress:正在生成回答...");
    verify(sessionService).completeStreamMessage(8L, RagChatController.EMPTY_RESPONSE_FALLBACK);
  }

  @Test
  @DisplayName("交互卡片提示应作为正文落库")
  void shouldPersistCardPromptAsMessageContent() {
    when(sessionService.prepareStreamMessage(1L, "开始模拟面试")).thenReturn(9L);
    when(sessionService.getStreamAnswer(1L, "开始模拟面试", 9L))
        .thenReturn(Flux.just("card:请选择目标岗位方向", "card_choice:[]"));

    controller.sendMessageStream(
        1L, new SendMessageRequest("开始模拟面试")).collectList().block();

    verify(sessionService).completeStreamMessage(9L, "请选择目标岗位方向");
  }

  @Test
  @DisplayName("引用终态事件应透传但不写入助手正文")
  void citationMetadataIsNotPersistedAsAnswer() {
    when(sessionService.prepareStreamMessage(1L, "RAG 引用")).thenReturn(10L);
    when(sessionService.getStreamAnswer(1L, "RAG 引用", 10L))
        .thenReturn(Flux.just(
            "回答正文 [1]",
            "citation:{\"sources\":[],\"confidence\":0.8,\"invalidCitations\":[]}"));

    List<ServerSentEvent<String>> events = controller.sendMessageStream(
        1L, new SendMessageRequest("RAG 引用")).collectList().block();

    assertThat(events).extracting(ServerSentEvent::data)
        .containsExactly(
            "回答正文 [1]",
            "citation:{\"sources\":[],\"confidence\":0.8,\"invalidCitations\":[]}");
    verify(sessionService).completeStreamMessage(10L, "回答正文 [1]");
  }

  @Test
  @DisplayName("客户端取消时应保存已生成正文并完成助手占位")
  void cancellationPersistsPartialAnswer() {
    when(sessionService.prepareStreamMessage(1L, "取消测试")).thenReturn(11L);
    when(sessionService.getStreamAnswer(1L, "取消测试", 11L))
        .thenReturn(Flux.concat(Flux.just("部分回答"), Flux.never()));

    Disposable subscription = controller.sendMessageStream(
        1L, new SendMessageRequest("取消测试")).subscribe();
    subscription.dispose();

    verify(sessionService).completeStreamMessage(11L, "部分回答");
  }

  @Test
  @DisplayName("创建回答流同步失败时也应完成助手占位")
  void synchronousStreamPreparationFailureCompletesPlaceholder() {
    when(sessionService.prepareStreamMessage(1L, "同步失败")).thenReturn(12L);
    when(sessionService.getStreamAnswer(1L, "同步失败", 12L))
        .thenThrow(new IllegalStateException("session unavailable"));

    assertThatThrownBy(() -> controller.sendMessageStream(
        1L, new SendMessageRequest("同步失败")).collectList().block())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("session unavailable");

    verify(sessionService).completeStreamMessage(
        12L, "【错误】回答生成失败：session unavailable");
  }
}
