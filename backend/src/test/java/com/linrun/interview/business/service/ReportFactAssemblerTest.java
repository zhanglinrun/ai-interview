package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.rag.model.EvidenceStatus;
import com.linrun.interview.business.mapper.InterviewCodeDraftMapper;
import com.linrun.interview.business.mapper.JobInterviewAnswerMapper;
import com.linrun.interview.business.mapper.JobInterviewQuestionMapper;
import com.linrun.interview.business.vo.AnswerAssessment;
import com.linrun.interview.business.constant.AnswerAssessmentStatus;
import com.linrun.interview.business.entity.InterviewCodeDraftEntity;
import com.linrun.interview.business.constant.JobCodingLanguage;
import com.linrun.interview.business.entity.JobInterviewAnswerEntity;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.JobInterviewStage;
import com.linrun.interview.business.constant.RecommendedAction;
import com.linrun.interview.rag.mapper.EvidenceSnapshotMapper;
import com.linrun.interview.rag.model.EvidenceSnapshotEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("报告客观事实装配")
class ReportFactAssemblerTest {

  private JobInterviewQuestionMapper questionMapper;
  private JobInterviewAnswerMapper answerMapper;
  private InterviewCodeDraftMapper draftMapper;
  private EvidenceSnapshotMapper snapshotMapper;
  private ObjectMapper objectMapper;
  private ReportFactAssembler assembler;

  @BeforeEach
  void setUp() {
    questionMapper = mock(JobInterviewQuestionMapper.class);
    answerMapper = mock(JobInterviewAnswerMapper.class);
    draftMapper = mock(InterviewCodeDraftMapper.class);
    snapshotMapper = mock(EvidenceSnapshotMapper.class);
    objectMapper = new ObjectMapper();
    assembler = new ReportFactAssembler(
        questionMapper, answerMapper, draftMapper, snapshotMapper, objectMapper);
  }

  @Test
  @DisplayName("Judge0 通过事实进入报告且不可被语言评价覆盖")
  void shouldPreserveAcceptedJudgeFact() throws Exception {
    JobInterviewQuestionEntity question = JobInterviewQuestionEntity.builder()
        .id(11L).userId(7L).sessionId(9L).questionIndex(0).sortOrder(10)
        .stage(JobInterviewStage.ALGORITHM).questionText("实现两数之和")
        .capabilityAtomId("ALGORITHM_FOUNDATION")
        .evidenceSnapshotId("snapshot-1").evidenceIdsJson("[\"ev-1\"]")
        .build();
    AnswerAssessment assessment = new AnswerAssessment(
        90, 80, "UNVERIFIED", EvidenceStatus.SUFFICIENT, 0.90d,
        RecommendedAction.SWITCH_TOPIC, "思路完整", List.of("ev-1"), false);
    JobInterviewAnswerEntity answer = JobInterviewAnswerEntity.builder()
        .id(21L).userId(7L).sessionId(9L).questionIndex(0).questionId(11L)
        .userAnswer("哈希表").feedback("思路完整")
        .assessmentStatus(AnswerAssessmentStatus.COMPLETED)
        .assessmentJson(objectMapper.writeValueAsString(assessment))
        .objectiveEvidenceIdsJson("[\"ev-1\"]")
        .answeredAt(LocalDateTime.now())
        .build();
    InterviewCodeDraftEntity draft = InterviewCodeDraftEntity.builder()
        .userId(7L).sessionId(9L).questionId(11L)
        .language(JobCodingLanguage.JAVA21)
        .judgeStatus("ACCEPTED")
        .judgeResultJson("{\"status\":\"ACCEPTED\",\"passedCount\":8,\"totalCount\":8,"
            + "\"timeMs\":57,\"memoryKb\":13132}")
        .build();
    EvidenceSnapshotEntity snapshot = new EvidenceSnapshotEntity();
    snapshot.setSnapshotId("snapshot-1");
    snapshot.setSourceAvailable(true);
    when(questionMapper.selectList(any())).thenReturn(List.of(question));
    when(answerMapper.selectList(any())).thenReturn(List.of(answer));
    when(draftMapper.selectList(any())).thenReturn(List.of(draft));
    when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot));

    var result = assembler.assemble(session(), "report-1");

    assertThat(result.facts()).singleElement().satisfies(fact -> {
      assertThat(fact.judgeStatus()).isEqualTo("ACCEPTED");
      assertThat(fact.passedCount()).isEqualTo(8);
      assertThat(fact.totalCount()).isEqualTo(8);
      assertThat(fact.codingLanguage()).isEqualTo("Java");
      assertThat(fact.executionTimeMs()).isEqualTo(57L);
      assertThat(fact.memoryKb()).isEqualTo(13132L);
      assertThat(fact.technicalCorrectness()).isEqualTo(100);
      assertThat(fact.completeness()).isEqualTo(100);
      assertThat(fact.sourceAvailable()).isTrue();
    });
    assertThat(result.capabilityEvidence()).singleElement().satisfies(evidence -> {
      assertThat(evidence.getObjectivePassed()).isTrue();
      assertThat(evidence.getEligibleForPromotion()).isTrue();
      assertThat(evidence.getTechnicalScore()).isEqualTo(100);
      assertThat(evidence.getCompletenessScore()).isEqualTo(100);
      assertThat(evidence.getConfidence()).isEqualByComparingTo(BigDecimal.ONE);
    });
  }

  @Test
  @DisplayName("Judge0 失败形成确定性低分证据以支持算法补练")
  void shouldCreateObjectiveFailureEvidence() {
    JobInterviewQuestionEntity question = JobInterviewQuestionEntity.builder()
        .id(13L).userId(7L).sessionId(9L).questionIndex(2).sortOrder(30)
        .stage(JobInterviewStage.ALGORITHM).questionText("实现最大子数组和")
        .capabilityAtomId("ALGORITHM_PROBLEM_SOLVING")
        .build();
    JobInterviewAnswerEntity answer = JobInterviewAnswerEntity.builder()
        .id(23L).userId(7L).sessionId(9L).questionIndex(2).questionId(13L)
        .userAnswer("[代码提交]").assessmentStatus(AnswerAssessmentStatus.COMPLETED)
        .assessmentJson("{\"status\":\"WRONG_ANSWER\"}")
        .assessmentConfidence(BigDecimal.ONE)
        .answeredAt(LocalDateTime.now())
        .build();
    InterviewCodeDraftEntity draft = InterviewCodeDraftEntity.builder()
        .userId(7L).sessionId(9L).questionId(13L)
        .language(JobCodingLanguage.JAVA21)
        .judgeStatus("WRONG_ANSWER")
        .judgeResultJson("{\"status\":\"WRONG_ANSWER\",\"passedCount\":2,"
            + "\"totalCount\":3}")
        .build();
    when(questionMapper.selectList(any())).thenReturn(List.of(question));
    when(answerMapper.selectList(any())).thenReturn(List.of(answer));
    when(draftMapper.selectList(any())).thenReturn(List.of(draft));

    var result = assembler.assemble(session(), "report-2");

    assertThat(result.facts()).singleElement().satisfies(fact -> {
      assertThat(fact.judgeStatus()).isEqualTo("WRONG_ANSWER");
      assertThat(fact.technicalCorrectness()).isZero();
      assertThat(fact.completeness()).isZero();
    });
    assertThat(result.capabilityEvidence()).singleElement().satisfies(evidence -> {
      assertThat(evidence.getObjectivePassed()).isFalse();
      assertThat(evidence.getTechnicalScore()).isZero();
      assertThat(evidence.getCompletenessScore()).isZero();
      assertThat(evidence.getConfidence()).isEqualByComparingTo(BigDecimal.ONE);
    });
  }

  @Test
  @DisplayName("损坏或缺失的结构化评价保持待评估而不猜分")
  void shouldKeepMalformedAssessmentPending() {
    JobInterviewQuestionEntity question = JobInterviewQuestionEntity.builder()
        .id(12L).userId(7L).sessionId(9L).questionIndex(1).sortOrder(20)
        .stage(JobInterviewStage.POSITION_TECH).questionText("解释事务传播")
        .capabilityAtomId("SPRING_APPLICATION")
        .build();
    JobInterviewAnswerEntity answer = JobInterviewAnswerEntity.builder()
        .id(22L).userId(7L).sessionId(9L).questionIndex(1).questionId(12L)
        .userAnswer("回答").assessmentStatus(AnswerAssessmentStatus.NEEDS_REVIEW)
        .assessmentJson("not-json").evidenceStatus(EvidenceStatus.NONE)
        .answeredAt(LocalDateTime.now())
        .build();
    when(questionMapper.selectList(any())).thenReturn(List.of(question));
    when(answerMapper.selectList(any())).thenReturn(List.of(answer));
    when(draftMapper.selectList(any())).thenReturn(List.of());

    var result = assembler.assemble(session(), "report-1");

    assertThat(result.facts()).singleElement().satisfies(fact -> {
      assertThat(fact.technicalCorrectness()).isNull();
      assertThat(fact.completeness()).isNull();
      assertThat(fact.assessmentStatus()).isEqualTo("NEEDS_REVIEW");
    });
    assertThat(result.capabilityEvidence()).singleElement()
        .satisfies(evidence -> assertThat(evidence.getEligibleForPromotion()).isFalse());
  }

  private JobInterviewSessionEntity session() {
    return JobInterviewSessionEntity.builder()
        .id(9L).userId(7L).sessionId("session-1").difficulty("mid").build();
  }
}
