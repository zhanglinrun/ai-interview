package com.linrun.interview.modules.knowledgebase;

import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import com.linrun.interview.modules.knowledgebase.service.RagChatSessionService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
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
}
