package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.service.AgentTraceInterpreter.PlaybackContext;
import com.linrun.interview.business.vo.AgentTraceActDTO;
import com.linrun.interview.business.vo.AgentTraceEventDTO;
import com.linrun.interview.business.vo.AgentTracePlaybackDTO;
import com.linrun.interview.business.vo.InterviewPlan;
import com.linrun.interview.business.vo.InterviewPlan.PlanTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent 轨迹解释")
class AgentTraceInterpreterTest {

  private final AgentTraceInterpreter interpreter = new AgentTraceInterpreter(new ObjectMapper());

  @Test
  @DisplayName("把 TurnDecision / 出题 / Critic 打回 / 重出 解成可读事件")
  void interpretsReflexionLoop() {
    List<AgentRunStepEntity> steps = List.of(
        step(null, 1, "planner", "plan", "topic=java",
            "{\"topics\":[{\"name\":\"Redis\",\"focus\":\"缓存\",\"questionCount\":1}],\"difficultyCurve\":\"由浅入深\",\"focusFromResume\":[],\"focusFromJd\":[]}"),
        step(0, 1, "orchestrator", "turn_decision", "questionIndex=0",
            "{\"action\":\"SWITCH_TOPIC\",\"targetCapability\":{\"id\":\"redis\",\"label\":\"Redis 缓存\"},\"rationale\":\"首题按大纲切换主题\",\"promptEvidenceIds\":[\"ev-1\"]}"),
        step(0, 2, "orchestrator", "state", "ASKING", "进入出题状态"),
        step(0, 3, "interviewer", "ask", "", "请说明 Redis 和本地缓存怎么选？"),
        step(0, 4, "orchestrator", "state", "CRITIQUING", "进入审题状态"),
        step(0, 5, "critic", "critique", "请说明 Redis 和本地缓存怎么选？",
            "{\"approved\":false,\"score\":40,\"feedback\":\"题面太宽\",\"retryHint\":\"收窄到过期策略\"}"),
        step(0, 6, "orchestrator", "state", "REFLEXION", "Critic 打回，round=1"),
        step(0, 7, "interviewer", "ask", "retryHint: 收窄到过期策略", "Redis key 过期后会发生什么？"),
        step(0, 8, "critic", "critique", "Redis key 过期后会发生什么？",
            "{\"approved\":true,\"score\":82,\"feedback\":\"具体\",\"retryHint\":\"\"}"),
        step(0, 9, "orchestrator", "finish", "",
            "{\"question\":\"Redis key 过期后会发生什么？\",\"followUpAction\":\"SWITCH_TOPIC\",\"selectedEvidenceIds\":[\"ev-1\"],\"criticApproved\":true}")
    );

    AgentTracePlaybackDTO playback = interpreter.interpret(
        "sess-1", List.of("sess-1"), steps, PlaybackContext.empty());

    assertThat(playback.stepCount()).isEqualTo(10);
    assertThat(playback.criticRejects()).isEqualTo(1);
    assertThat(playback.reflexionRounds()).isGreaterThanOrEqualTo(2);
    assertThat(playback.emptyReason()).isNull();
    assertThat(playback.plan()).isNotNull();
    assertThat(playback.plan().topics()).extracting(PlanTopic::name).containsExactly("Redis");

    assertThat(playback.acts()).hasSize(2);
    AgentTraceActDTO planning = playback.acts().get(0);
    assertThat(planning.questionIndex()).isNull();
    assertThat(planning.events().get(0).headline()).contains("Planner");

    AgentTraceActDTO question = playback.acts().get(1);
    assertThat(question.followUpAction()).isEqualTo("SWITCH_TOPIC");
    assertThat(question.criticApproved()).isTrue();
    assertThat(question.finalQuestion()).contains("过期");
    assertThat(question.statePath()).contains("ASKING", "CRITIQUING", "REFLEXION");

    AgentTraceEventDTO reject = question.events().stream()
        .filter(event -> "critique".equals(event.action()) && Boolean.FALSE.equals(event.approved()))
        .findFirst()
        .orElseThrow();
    assertThat(reject.headline()).contains("打回");
    assertThat(reject.retryHint()).isEqualTo("收窄到过期策略");
    assertThat(reject.score()).isEqualTo(40);
  }

  @Test
  @DisplayName("有大纲但无步骤时给出 STEPS_MISSING 说明")
  void explainsMissingSteps() {
    AgentTracePlaybackDTO playback = interpreter.interpret(
        "sess-1",
        List.of("sess-1"),
        List.of(),
        new PlaybackContext(true, true,
            new InterviewPlan(List.of(new PlanTopic("RAG", "检索", 1)), "由浅入深", List.of(), List.of())));

    assertThat(playback.emptyReason()).isEqualTo("STEPS_MISSING");
    assertThat(playback.emptyHint()).contains("agent_steps");
  }

  @Test
  @DisplayName("工具步骤保留工具名")
  void keepsToolName() {
    AgentTracePlaybackDTO playback = interpreter.interpret(
        "sess-2",
        List.of("sess-2"),
        List.of(step(0, 1, "interviewer", "readResume", "tool arguments redacted",
            "resume.read executed; result redacted")),
        PlaybackContext.empty());

    assertThat(playback.toolCalls()).isEqualTo(1);
    assertThat(playback.acts().get(0).events().get(0).headline()).isEqualTo("Tool · readResume");
  }

  @Test
  @DisplayName("chat 步骤不算工具调用")
  void chatIsNotATool() {
    AgentTracePlaybackDTO playback = interpreter.interpret(
        "sess-3",
        List.of("sess-3"),
        List.of(step(0, 1, "interviewer", "chat", "user: hi", "请说明 Redis 过期")),
        PlaybackContext.empty());

    assertThat(playback.toolCalls()).isZero();
    assertThat(playback.acts().get(0).events().get(0).headline()).startsWith("Chat ·");
  }

  private static AgentRunStepEntity step(Integer questionIndex, int order, String role,
                                         String action, String input, String observation) {
    return AgentRunStepEntity.builder()
        .id((long) order)
        .questionIndex(questionIndex)
        .stepOrder(order)
        .role(role)
        .action(action)
        .actionInput(input)
        .observation(observation)
        .build();
  }
}
