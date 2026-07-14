package com.linrun.interview.modules.interview.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.modules.interview.agent.AgentOrchestrationProperties;
import com.linrun.interview.modules.interview.mapper.CandidateMemoryMapper;
import com.linrun.interview.modules.interview.memory.CandidateMemoryService.CandidateMemoryProfileDTO;
import com.linrun.interview.modules.interview.model.InterviewQuestionDTO;
import com.linrun.interview.modules.interview.model.InterviewReportDTO;
import com.linrun.interview.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("候选人能力画像测试")
@SuppressWarnings("unchecked")
class CandidateMemoryServiceTest {

  @Test
  @DisplayName("逐题评估按能力原子保存分数与证据 ID，不再二次调用 LLM")
  void savesEvaluationObservation() {
    CandidateMemoryMapper mapper = mock(CandidateMemoryMapper.class);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    CandidateMemoryService service = service(mapper);

    InterviewSessionEntity session = session("session-1");
    InterviewQuestionDTO question = InterviewQuestionDTO.createAgent(
        0, "如何保证缓存一致性？", "skill:java-backend:redis", "Redis",
        "Redis", false, null, "skill:java-backend:redis", "SWITCH_TOPIC",
        List.of("chunk:101"));
    InterviewReportDTO report = report(new QuestionEvaluation(
        0, question.question(), question.category(), "先更新数据库再删缓存，因为……",
        82, "能说明失败窗口与工程取舍。"));

    service.extractAndSaveQuietly(session, report, List.of(question));

    ArgumentCaptor<CandidateMemoryEntity> captor =
        ArgumentCaptor.forClass(CandidateMemoryEntity.class);
    verify(mapper).insert(captor.capture());
    CandidateMemoryEntity saved = captor.getValue();
    assertThat(saved.getCapabilityAtomId()).isEqualTo("skill:java-backend:redis");
    assertThat(saved.getMasteryScore()).isEqualTo(82);
    assertThat(saved.getKind()).isEqualTo(CandidateMemoryEntity.KIND_STRENGTH);
    assertThat(saved.getQuestionIndex()).isZero();
    assertThat(saved.getEvidenceIdsJson()).isEqualTo("[\"chunk:101\"]");
  }

  @Test
  @DisplayName("并发重投触发唯一键冲突时按幂等成功处理")
  void treatsDuplicateInsertAsIdempotentSuccess() {
    CandidateMemoryMapper mapper = mock(CandidateMemoryMapper.class);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(mapper.insert(any(CandidateMemoryEntity.class)))
        .thenThrow(new DuplicateKeyException("duplicate observation"));
    CandidateMemoryService service = service(mapper);
    InterviewQuestionDTO question = InterviewQuestionDTO.createAgent(
        0, "如何保证缓存一致性？", "skill:java-backend:redis", "Redis",
        "Redis", false, null, "skill:java-backend:redis", "SWITCH_TOPIC",
        List.of("chunk:101"));

    assertThatCode(() -> service.extractAndSave(
        session("session-1"),
        report(new QuestionEvaluation(
            0, question.question(), question.category(), "回答内容", 82, "评估依据")),
        List.of(question)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("至少三次观测且跨两场面试才标记为已验证")
  void verifiesOnlyWithCrossSessionEvidence() {
    CandidateMemoryMapper mapper = mock(CandidateMemoryMapper.class);
    LocalDateTime now = LocalDateTime.now();
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        observation(3L, "session-2", 0, 90, CandidateMemoryEntity.KIND_STRENGTH,
            now, "[\"chunk:3\"]"),
        observation(2L, "session-1", 1, 70, CandidateMemoryEntity.KIND_DEVELOPING,
            now.minusDays(1), null),
        observation(1L, "session-1", 0, 80, CandidateMemoryEntity.KIND_STRENGTH,
            now.minusDays(1), null)));
    CandidateMemoryService service = service(mapper);

    List<CandidateMemoryProfileDTO> profiles = service.getProfile(1L, "java-backend");

    assertThat(profiles).hasSize(1);
    CandidateMemoryProfileDTO profile = profiles.getFirst();
    assertThat(profile.averageScore()).isEqualTo(80);
    assertThat(profile.observationCount()).isEqualTo(3);
    assertThat(profile.sessionCount()).isEqualTo(2);
    assertThat(profile.masteryLevel()).isEqualTo("STRENGTH");
    assertThat(profile.verificationState()).isEqualTo("VERIFIED");
    assertThat(profile.confidenceLevel()).isEqualTo("HIGH");
    assertThat(profile.latestEvidenceIds()).containsExactly("chunk:3");
  }

  private CandidateMemoryService service(CandidateMemoryMapper mapper) {
    AgentOrchestrationProperties properties = new AgentOrchestrationProperties();
    return new CandidateMemoryService(mapper, new ObjectMapper(), properties);
  }

  private InterviewSessionEntity session(String sessionId) {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId(sessionId);
    session.setUserId(1L);
    session.setSkillId("java-backend");
    return session;
  }

  private InterviewReportDTO report(QuestionEvaluation evaluation) {
    return new InterviewReportDTO(
        "session-1", 1, evaluation.score(), List.of(), List.of(evaluation),
        "总体评价", List.of(), List.of(), List.of());
  }

  private CandidateMemoryEntity observation(Long id, String sessionId, int questionIndex,
                                             int score, String kind, LocalDateTime createdAt,
                                             String evidenceIdsJson) {
    return CandidateMemoryEntity.builder()
        .id(id)
        .userId(1L)
        .skillId("java-backend")
        .capabilityAtomId("skill:java-backend:redis")
        .topic("Redis")
        .kind(kind)
        .questionIndex(questionIndex)
        .masteryScore(score)
        .evidence("评估依据")
        .evidenceIdsJson(evidenceIdsJson)
        .sessionId(sessionId)
        .createdAt(createdAt)
        .build();
  }
}
