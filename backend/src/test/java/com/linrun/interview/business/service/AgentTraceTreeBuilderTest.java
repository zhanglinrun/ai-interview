package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.vo.AgentTraceSpanDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent trace 树")
class AgentTraceTreeBuilderTest {

  private final AgentTraceTreeBuilder builder = new AgentTraceTreeBuilder(new ObjectMapper());

  @Test
  @DisplayName("同一题内：决策、Chat、Tool 收在第 1 题下，tool 挂在 chat 下")
  void nestsToolUnderChatInQuestionPhase() {
    List<AgentTraceSpanDTO> forest = builder.build(List.of(
        step("decision-1", "root-span", "orchestrator", "turn_decision", null),
        step("chat-1", "root-span", "interviewer", "chat",
            "{\"kind\":\"chat\",\"model\":\"qwen\",\"inputTokens\":12,\"outputTokens\":8}"),
        step("tool-1", "chat-1", "interviewer", "readResume",
            "{\"kind\":\"tool\"}"),
        step("chat-2", "root-span", "critic", "chat",
            "{\"kind\":\"chat\",\"model\":\"qwen\"}")));

    assertThat(forest).hasSize(1);
    AgentTraceSpanDTO root = forest.getFirst();
    assertThat(root.title()).isEqualTo("第 1 题");
    assertThat(root.children()).hasSize(3);
    assertThat(root.children().get(0).action()).isEqualTo("turn_decision");
    assertThat(root.children().get(1).kind()).isEqualTo("chat");
    assertThat(root.children().get(1).title()).isEqualTo("Chat · Interviewer");
    assertThat(root.children().get(1).children()).hasSize(1);
    assertThat(root.children().get(1).children().getFirst().action()).isEqualTo("readResume");
    assertThat(root.children().get(1).model()).isEqualTo("qwen");
    assertThat(root.children().get(1).inputTokens()).isEqualTo(12);
    assertThat(root.children().get(2).title()).isEqualTo("Chat · Critic");
  }

  @Test
  @DisplayName("创建会话：Planner 与首题拆成定大纲 / 第 1 题，丢掉重复日记")
  void splitsSharedRootIntoPlanningAndQuestion() {
    List<AgentTraceSpanDTO> forest = builder.build(List.of(
        step("chat-p", "root-span", "planner", "chat", "{\"kind\":\"chat\"}"),
        step("plan-1", "root-span", "planner", "plan", null),
        step("chat-i", "root-span", "interviewer", "chat", "{\"kind\":\"chat\"}"),
        step("chat-c", "root-span", "critic", "chat", "{\"kind\":\"chat\"}"),
        step("td-1", "root-span", "orchestrator", "turn_decision", null),
        step("st-1", "root-span", "orchestrator", "state", null),
        step("ask-1", "root-span", "interviewer", "ask", null),
        step("st-2", "root-span", "orchestrator", "state", null),
        step("cr-1", "root-span", "critic", "critique", null),
        step("fin-1", "root-span", "orchestrator", "finish", null)));

    assertThat(forest).extracting(AgentTraceSpanDTO::title)
        .containsExactly("定大纲", "第 1 题");
    assertThat(forest.get(0).children()).extracting(AgentTraceSpanDTO::title)
        .containsExactly("Chat · Planner");
    assertThat(forest.get(1).children()).extracting(AgentTraceSpanDTO::title)
        .containsExactly("逐轮决策", "Chat · Interviewer", "Chat · Critic", "本轮定题");
  }

  @Test
  @DisplayName("生产顺序：Q1 chats+diary 后再 Q2 chats+diary，第 2 题拥有自己的 Chat")
  void splitsProductionOrderByQuestionIndex() {
    List<AgentTraceSpanDTO> forest = builder.build(List.of(
        step("q1-chat-i", "root", "interviewer", "chat", "{\"kind\":\"chat\"}", 0),
        step("q1-chat-c", "root", "critic", "chat", "{\"kind\":\"chat\"}", 0),
        step("q1-td", "root", "orchestrator", "turn_decision", null, 0),
        step("q1-ask", "root", "interviewer", "ask", null, 0),
        step("q1-cr", "root", "critic", "critique", null, 0),
        step("q1-fin", "root", "orchestrator", "finish", null, 0),
        step("q2-chat-i", "root", "interviewer", "chat", "{\"kind\":\"chat\"}", 1),
        step("q2-chat-c", "root", "critic", "chat", "{\"kind\":\"chat\"}", 1),
        step("q2-td", "root", "orchestrator", "turn_decision", null, 1),
        step("q2-ask", "root", "interviewer", "ask", null, 1),
        step("q2-cr", "root", "critic", "critique", null, 1),
        step("q2-fin", "root", "orchestrator", "finish", null, 1),
        step("q8-chat-i", "root", "interviewer", "chat", "{\"kind\":\"chat\"}", 7),
        step("q8-chat-c", "root", "critic", "chat", "{\"kind\":\"chat\"}", 7),
        step("q8-td", "root", "orchestrator", "turn_decision", null, 7),
        step("q8-ask", "root", "interviewer", "ask", null, 7),
        step("q8-cr", "root", "critic", "critique", null, 7),
        step("q8-fin", "root", "orchestrator", "finish", null, 7)));

    assertThat(forest).extracting(AgentTraceSpanDTO::title)
        .containsExactly("第 1 题", "第 2 题", "第 8 题");
    assertThat(forest.get(1).children()).extracting(AgentTraceSpanDTO::title)
        .contains("Chat · Interviewer", "Chat · Critic");
    assertThat(forest.get(2).children()).extracting(AgentTraceSpanDTO::title)
        .contains("Chat · Interviewer", "Chat · Critic")
        .doesNotContain("ask", "critique");
  }

  @Test
  @DisplayName("旧场次：finish 之后的 chat 开新相")
  void legacyChatAfterFinishStartsNewPhase() {
    List<AgentTraceSpanDTO> forest = builder.build(List.of(
        step("q1-td", "root", "orchestrator", "turn_decision", null),
        step("q1-fin", "root", "orchestrator", "finish", null),
        step("q2-chat", "root", "interviewer", "chat", "{\"kind\":\"chat\"}")));

    assertThat(forest).extracting(AgentTraceSpanDTO::title)
        .containsExactly("第 1 题", "第 2 题");
    assertThat(forest.get(1).children()).extracting(AgentTraceSpanDTO::title)
        .containsExactly("Chat · Interviewer");
  }

  @Test
  @DisplayName("评估相独立，不挂在最后一题下面")
  void splitsEvaluatingPhase() {
    List<AgentTraceSpanDTO> forest = builder.build(List.of(
        step("q1-fin", "root", "orchestrator", "finish", null, 0),
        AgentRunStepEntity.builder()
            .spanId("eval-1")
            .parentSpanId("eval-root")
            .role("evaluator")
            .action("enqueue_evaluation")
            .status("COMPLETED")
            .build(),
        AgentRunStepEntity.builder()
            .spanId("eval-2")
            .parentSpanId("eval-root")
            .role("evaluator")
            .action("evaluate_completed")
            .status("COMPLETED")
            .build()));

    assertThat(forest).extracting(AgentTraceSpanDTO::title)
        .containsExactly("第 1 题", "评估");
    assertThat(forest.get(1).children()).extracting(AgentTraceSpanDTO::title)
        .containsExactly("评估入队", "评估完成");
  }

  @Test
  @DisplayName("旧步骤没有 spanId 时仍能摊成定大纲")
  void fallsBackForLegacySteps() {
    List<AgentTraceSpanDTO> forest = builder.build(List.of(
        AgentRunStepEntity.builder()
            .id(9L)
            .role("planner")
            .action("plan")
            .actionInput("topic=java")
            .observation("{\"topics\":[]}")
            .status("COMPLETED")
            .build()));

    assertThat(forest).hasSize(1);
    assertThat(forest.getFirst().title()).isEqualTo("定大纲");
    assertThat(forest.getFirst().children()).hasSize(1);
    assertThat(forest.getFirst().children().getFirst().spanId()).isEqualTo("step-9");
    assertThat(forest.getFirst().children().getFirst().action()).isEqualTo("plan");
  }

  private static AgentRunStepEntity step(String spanId, String parent, String role,
                                         String action, String metadata) {
    return step(spanId, parent, role, action, metadata, null);
  }

  private static AgentRunStepEntity step(String spanId, String parent, String role,
                                         String action, String metadata, Integer questionIndex) {
    return AgentRunStepEntity.builder()
        .spanId(spanId)
        .parentSpanId(parent)
        .role(role)
        .action(action)
        .actionInput("in")
        .observation("out")
        .status("COMPLETED")
        .latencyMs(10L)
        .metadataJson(metadata)
        .questionIndex(questionIndex)
        .build();
  }
}
