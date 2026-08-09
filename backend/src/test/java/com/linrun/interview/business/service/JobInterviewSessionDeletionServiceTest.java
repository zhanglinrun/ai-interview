package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.business.mapper.CodingAttemptMapper;
import com.linrun.interview.business.mapper.CodingDraftMapper;
import com.linrun.interview.business.mapper.JudgeSubmissionMapper;
import com.linrun.interview.business.entity.CodingAttemptEntity;
import com.linrun.interview.business.entity.CodingDraftEntity;
import com.linrun.interview.business.entity.JudgeSubmissionEntity;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.mapper.AgentRunStepMapper;
import com.linrun.interview.business.mapper.CandidateMemoryMapper;
import com.linrun.interview.business.service.CandidateMemoryEntity;
import com.linrun.interview.business.mapper.InterviewCodeDraftMapper;
import com.linrun.interview.business.mapper.InterviewCommandMapper;
import com.linrun.interview.business.mapper.InterviewSessionEventMapper;
import com.linrun.interview.business.mapper.JobInterviewAnswerMapper;
import com.linrun.interview.business.mapper.JobInterviewQuestionMapper;
import com.linrun.interview.business.mapper.PreparationRunMapper;
import com.linrun.interview.business.entity.InterviewCodeDraftEntity;
import com.linrun.interview.business.entity.InterviewCommandEntity;
import com.linrun.interview.business.entity.InterviewSessionEventEntity;
import com.linrun.interview.business.entity.JobInterviewAnswerEntity;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import com.linrun.interview.business.entity.PreparationRunEntity;
import com.linrun.interview.rag.mapper.EvidenceSnapshotMapper;
import com.linrun.interview.rag.mapper.EvidenceSnapshotRefMapper;
import com.linrun.interview.rag.model.EvidenceSnapshotEntity;
import com.linrun.interview.rag.model.EvidenceSnapshotRefEntity;
import com.linrun.interview.business.mapper.CapabilityEvidenceMapper;
import com.linrun.interview.business.mapper.InterviewReportMapper;
import com.linrun.interview.business.mapper.LlmUsageRecordMapper;
import com.linrun.interview.business.mapper.TrainingTaskMapper;
import com.linrun.interview.business.entity.CapabilityEvidenceEntity;
import com.linrun.interview.business.entity.InterviewReportEntity;
import com.linrun.interview.business.entity.LlmUsageRecordEntity;
import com.linrun.interview.business.entity.TrainingTaskEntity;
import com.linrun.interview.business.service.CapabilityProfileService;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位实战会话删除兼容")
class JobInterviewSessionDeletionServiceTest {

  @Mock
  private InterviewCodeDraftMapper interviewCodeDraftMapper;
  @Mock
  private JobInterviewAnswerMapper answerMapper;
  @Mock
  private JobInterviewQuestionMapper questionMapper;
  @Mock
  private InterviewCommandMapper commandMapper;
  @Mock
  private InterviewSessionEventMapper eventMapper;
  @Mock
  private PreparationRunMapper preparationRunMapper;
  @Mock
  private InterviewReportMapper reportMapper;
  @Mock
  private CapabilityEvidenceMapper capabilityEvidenceMapper;
  @Mock
  private TrainingTaskMapper trainingTaskMapper;
  @Mock
  private LlmUsageRecordMapper usageRecordMapper;
  @Mock
  private AgentRunStepMapper agentRunStepMapper;
  @Mock
  private CandidateMemoryMapper candidateMemoryMapper;
  @Mock
  private CodingAttemptMapper codingAttemptMapper;
  @Mock
  private CodingDraftMapper codingDraftMapper;
  @Mock
  private JudgeSubmissionMapper judgeSubmissionMapper;
  @Mock
  private EvidenceSnapshotMapper evidenceSnapshotMapper;
  @Mock
  private EvidenceSnapshotRefMapper evidenceSnapshotRefMapper;
  @Mock
  private CapabilityProfileService capabilityProfileService;

  @InjectMocks
  private JobInterviewSessionDeletionService service;

  @BeforeEach
  void initializeMybatisMetadata() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(
        new MybatisConfiguration(), "job-interview-deletion-test");
    List.of(
        InterviewCodeDraftEntity.class,
        JobInterviewAnswerEntity.class,
        JobInterviewQuestionEntity.class,
        InterviewCommandEntity.class,
        InterviewSessionEventEntity.class,
        PreparationRunEntity.class,
        InterviewReportEntity.class,
        CapabilityEvidenceEntity.class,
        TrainingTaskEntity.class,
        LlmUsageRecordEntity.class,
        AgentRunStepEntity.class,
        CandidateMemoryEntity.class,
        CodingAttemptEntity.class,
        CodingDraftEntity.class,
        JudgeSubmissionEntity.class,
        EvidenceSnapshotEntity.class,
        EvidenceSnapshotRefEntity.class)
        .forEach(entity -> TableInfoHelper.initTableInfo(assistant, entity));
  }

  @Test
  @DisplayName("先解除长期学习投影溯源，再按外键顺序删除全部原始会话数据")
  void shouldDeleteRawArtifactsAndDetachLongTermProjection() {
    InterviewReportEntity report = new InterviewReportEntity();
    report.setReportId("report-1");
    CapabilityEvidenceEntity evidence = new CapabilityEvidenceEntity();
    evidence.setCapabilityAtomId("java.concurrent");
    PreparationRunEntity preparation = new PreparationRunEntity();
    preparation.setRunId("run-1");
    CodingAttemptEntity attempt = new CodingAttemptEntity();
    attempt.setId(41L);
    EvidenceSnapshotEntity snapshot = new EvidenceSnapshotEntity();
    snapshot.setSnapshotId("evidence-1");

    when(reportMapper.selectList(any())).thenReturn(List.of(report));
    when(capabilityEvidenceMapper.selectList(any())).thenReturn(List.of(evidence));
    when(preparationRunMapper.selectList(any())).thenReturn(List.of(preparation));
    when(codingAttemptMapper.selectList(any())).thenReturn(List.of(attempt));
    when(evidenceSnapshotMapper.selectList(any())).thenReturn(List.of(snapshot));

    service.deleteOwnedSessionArtifacts(7L, 11L, "session-1");

    InOrder projectionOrder = inOrder(
        trainingTaskMapper, capabilityEvidenceMapper, usageRecordMapper,
        candidateMemoryMapper, reportMapper);
    projectionOrder.verify(trainingTaskMapper).update(isNull(), any());
    projectionOrder.verify(capabilityEvidenceMapper).update(isNull(), any());
    projectionOrder.verify(usageRecordMapper).update(isNull(), any());
    projectionOrder.verify(candidateMemoryMapper).update(isNull(), any());
    projectionOrder.verify(reportMapper).delete(any());

    InOrder algorithmOrder = inOrder(
        judgeSubmissionMapper, codingDraftMapper, codingAttemptMapper);
    algorithmOrder.verify(judgeSubmissionMapper).delete(any());
    algorithmOrder.verify(codingDraftMapper).delete(any());
    algorithmOrder.verify(codingAttemptMapper).delete(any());

    InOrder runtimeOrder = inOrder(
        interviewCodeDraftMapper, answerMapper, questionMapper,
        commandMapper, eventMapper, agentRunStepMapper);
    runtimeOrder.verify(interviewCodeDraftMapper).delete(any());
    runtimeOrder.verify(answerMapper).delete(any());
    runtimeOrder.verify(questionMapper).delete(any());
    runtimeOrder.verify(commandMapper).delete(any());
    runtimeOrder.verify(eventMapper).delete(any());
    runtimeOrder.verify(agentRunStepMapper).delete(any());

    InOrder snapshotOrder = inOrder(
        evidenceSnapshotRefMapper, evidenceSnapshotMapper, preparationRunMapper);
    snapshotOrder.verify(evidenceSnapshotRefMapper).delete(any());
    snapshotOrder.verify(evidenceSnapshotMapper).delete(any());
    snapshotOrder.verify(preparationRunMapper).delete(any());

    verify(capabilityProfileService).refresh(7L, "java.concurrent");
    verify(capabilityEvidenceMapper, never()).delete(any());
    verify(trainingTaskMapper, never()).delete(any());
    verify(usageRecordMapper, never()).delete(any());
    verify(candidateMemoryMapper, never()).delete(any());

    verify(commandMapper).delete(argThat(
        wrapper -> hasParams(wrapper, 7L, "session-1")));
    verify(interviewCodeDraftMapper).delete(argThat(
        wrapper -> hasParams(wrapper, 7L, 11L)));
    verify(reportMapper).delete(argThat(wrapper -> hasParams(wrapper, 7L, 11L)));
    verify(preparationRunMapper).delete(argThat(
        wrapper -> hasParams(wrapper, 7L, "session-1")));
  }

  @Test
  @DisplayName("参数不完整时不执行任何删除")
  void shouldRejectIncompleteDeletionScope() {
    assertThatThrownBy(() -> service.deleteOwnedSessionArtifacts(7L, null, "session-1"))
        .isInstanceOf(BusinessException.class);

    verify(reportMapper, never()).selectList(any());
    verify(interviewCodeDraftMapper, never()).delete(any());
  }

  private boolean hasParams(Wrapper<?> wrapper, Object... expected) {
    if (!(wrapper instanceof AbstractWrapper<?, ?, ?> actual)) {
      return false;
    }
    actual.getSqlSegment();
    return Arrays.stream(expected)
        .allMatch(value -> actual.getParamNameValuePairs().containsValue(value));
  }
}

