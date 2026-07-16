package com.linrun.interview.modules.jobinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linrun.interview.common.evidence.EvidenceStatus;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.AbortCommand;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.CommandEnvelope;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.CommandResult;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.SubmitAnswerCommand;
import com.linrun.interview.modules.jobinterview.mapper.InterviewCodeDraftMapper;
import com.linrun.interview.modules.jobinterview.model.AnswerAssessment;
import com.linrun.interview.modules.jobinterview.model.InterviewCodeDraftEntity;
import com.linrun.interview.modules.jobinterview.model.InterviewCommandEntity;
import com.linrun.interview.modules.jobinterview.model.InterviewCommandStatus;
import com.linrun.interview.modules.jobinterview.model.InterviewCommandType;
import com.linrun.interview.modules.jobinterview.model.JobInterviewQuestionEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionEntity;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionStatus;
import com.linrun.interview.modules.jobinterview.model.JobInterviewStage;
import com.linrun.interview.modules.jobinterview.model.RecommendedAction;
import com.linrun.interview.modules.jobinterview.service.JobInterviewCommandPersistenceService
    .CommandExecution;
import com.linrun.interview.modules.jobinterview.service.JobInterviewCommandPersistenceService
    .CommandReservation;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位实战事务外编排")
class JobInterviewRuntimeServiceTest {

  @Mock
  private JobInterviewCommandPersistenceService commandPersistence;
  @Mock
  private JobInterviewSessionPersistenceService sessionPersistence;
  @Mock
  private JobInterviewLifecycleService lifecycleService;
  @Mock
  private JobInterviewAssessmentPort assessmentPort;
  @Mock
  private JobInterviewCodingPort codingPort;
  @Mock
  private JobInterviewViewAssembler viewAssembler;
  @Mock
  private InterviewCodeDraftMapper codeDraftMapper;
  @Mock
  private ObjectProvider<JobInterviewCompletionPublisher> completionPublishers;

  private JobInterviewRuntimeService service;

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "job-interview-runtime-test"),
        InterviewCodeDraftEntity.class);
    service = new JobInterviewRuntimeService(
        commandPersistence, sessionPersistence, lifecycleService, assessmentPort, codingPort,
        viewAssembler, codeDraftMapper, completionPublishers);
  }

  @Test
  @DisplayName("首次进入算法阶段应从冻结题目版本返回语言模板而不是空编辑器")
  void shouldReturnStarterTemplateBeforeFirstSave() {
    JobInterviewSessionEntity session = session(JobInterviewSessionStatus.IN_PROGRESS, 3L);
    session.setCodingLanguage(
        com.linrun.interview.modules.jobinterview.model.JobCodingLanguage.JAVA21);
    JobInterviewQuestionEntity question = JobInterviewQuestionEntity.builder()
        .id(21L).sessionId(11L).userId(7L).stage(JobInterviewStage.ALGORITHM)
        .questionTemplateVersion("5").build();
    when(lifecycleService.reconcileOwned(7L, "session-1")).thenReturn(session);
    when(sessionPersistence.requireQuestion(7L, 11L, 21L)).thenReturn(question);
    when(codeDraftMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(codingPort.starter(
        question,
        com.linrun.interview.modules.jobinterview.model.JobCodingLanguage.JAVA21))
        .thenReturn(new JobInterviewCodingPort.CodeTemplate(
            "class Solution {}", "int[] twoSum(int[] nums, int target)"));

    var draft = service.getCodeDraft(7L, "session-1", 21L);

    assertThat(draft.judgeStatus()).isEqualTo("INITIAL");
    assertThat(draft.sourceCode()).contains("class Solution");
    assertThat(draft.functionSignature()).contains("twoSum");
  }

  @Test
  @DisplayName("单题 LLM 评价必须显式传 userId，且发生在指令占位与完成事务之间")
  void shouldPassExplicitUserIdToAssessmentPort() {
    JobInterviewSessionEntity session = session(JobInterviewSessionStatus.IN_PROGRESS, 2L);
    session.setCurrentQuestionId(21L);
    JobInterviewQuestionEntity question = JobInterviewQuestionEntity.builder()
        .id(21L).sessionId(11L).userId(7L).stage(JobInterviewStage.PROJECT_DEEP_DIVE)
        .build();
    InterviewCommandEntity command = InterviewCommandEntity.builder()
        .id(31L).userId(7L).sessionId("session-1").commandId("answer-1")
        .commandType(InterviewCommandType.SUBMIT_ANSWER).expectedSessionVersion(2L)
        .status(InterviewCommandStatus.PROCESSING).build();
    CommandReservation reservation = new CommandReservation(command, session, null, true);
    AnswerAssessment assessment = new AnswerAssessment(
        80, 80, "UNVERIFIED", EvidenceStatus.NONE, 0.8d,
        RecommendedAction.SWITCH_TOPIC, "ok", List.of(), false);
    var outcome = new JobInterviewAssessmentPort.AssessmentOutcome(
        assessment, null, 20L, 30, 10, 0, null);
    CommandResult result = result(
        "answer-1", InterviewCommandType.SUBMIT_ANSWER,
        JobInterviewSessionStatus.IN_PROGRESS, 3L);
    when(lifecycleService.reconcileOwned(7L, "session-1")).thenReturn(session);
    when(commandPersistence.reserve(
        7L, "session-1", "answer-1", InterviewCommandType.SUBMIT_ANSWER, 2L))
        .thenReturn(reservation);
    when(sessionPersistence.requireQuestion(7L, 11L, 21L)).thenReturn(question);
    when(assessmentPort.assess(7L, session, question, "我的回答")).thenReturn(outcome);
    when(commandPersistence.completeAnswer(reservation, 21L, "我的回答", outcome))
        .thenReturn(new CommandExecution(result, false, 7L));

    CommandResult actual = service.submitAnswer(
        7L, "session-1", new SubmitAnswerCommand("answer-1", 2L, 21L, "我的回答"));

    assertThat(actual.sessionVersion()).isEqualTo(3L);
    verify(assessmentPort).assess(7L, session, question, "我的回答");
  }

  @Test
  @DisplayName("完成事务提交后才发布报告端口，ABORTED 不发布")
  void shouldPublishOnlyCompletedSession() {
    JobInterviewCompletionPublisher publisher = mock(JobInterviewCompletionPublisher.class);
    when(completionPublishers.orderedStream()).thenReturn(Stream.of(publisher));
    JobInterviewSessionEntity session = session(JobInterviewSessionStatus.IN_PROGRESS, 4L);
    InterviewCommandEntity finishCommand = InterviewCommandEntity.builder()
        .id(32L).userId(7L).sessionId("session-1").commandId("finish-1")
        .commandType(InterviewCommandType.FINISH).expectedSessionVersion(4L)
        .status(InterviewCommandStatus.PROCESSING).build();
    CommandReservation finishReservation = new CommandReservation(
        finishCommand, session, null, true);
    CommandResult completed = result(
        "finish-1", InterviewCommandType.FINISH,
        JobInterviewSessionStatus.COMPLETED, 5L);
    when(lifecycleService.reconcileOwned(7L, "session-1")).thenReturn(session);
    when(commandPersistence.reserve(
        7L, "session-1", "finish-1", InterviewCommandType.FINISH, 4L))
        .thenReturn(finishReservation);
    when(commandPersistence.completeFinish(finishReservation))
        .thenReturn(new CommandExecution(completed, true, 7L));

    service.finish(7L, "session-1", new CommandEnvelope("finish-1", 4L));

    verify(publisher).publishCompleted("session-1", 7L);
  }

  @Test
  @DisplayName("显式终止只写 ABORTED，不触发正式报告")
  void shouldNotPublishForAbortedSession() {
    JobInterviewCompletionPublisher publisher = mock(JobInterviewCompletionPublisher.class);
    JobInterviewSessionEntity session = session(JobInterviewSessionStatus.IN_PROGRESS, 4L);
    InterviewCommandEntity abortCommand = InterviewCommandEntity.builder()
        .id(33L).userId(7L).sessionId("session-1").commandId("abort-1")
        .commandType(InterviewCommandType.ABORT).expectedSessionVersion(4L)
        .status(InterviewCommandStatus.PROCESSING).build();
    CommandReservation reservation = new CommandReservation(abortCommand, session, null, true);
    CommandResult aborted = result(
        "abort-1", InterviewCommandType.ABORT, JobInterviewSessionStatus.ABORTED, 5L);
    when(lifecycleService.reconcileOwned(7L, "session-1")).thenReturn(session);
    when(commandPersistence.reserve(
        7L, "session-1", "abort-1", InterviewCommandType.ABORT, 4L))
        .thenReturn(reservation);
    when(commandPersistence.completeAbort(reservation, "用户终止"))
        .thenReturn(new CommandExecution(aborted, false, 7L));

    service.abort(7L, "session-1", new AbortCommand("abort-1", 4L, "用户终止"));

    verifyNoInteractions(publisher);
  }

  private JobInterviewSessionEntity session(JobInterviewSessionStatus status, long version) {
    return JobInterviewSessionEntity.builder()
        .id(11L).userId(7L).sessionId("session-1").status(status)
        .sessionVersion(version).build();
  }

  private CommandResult result(
      String commandId,
      InterviewCommandType type,
      JobInterviewSessionStatus status,
      long version
  ) {
    return new CommandResult(
        commandId, type, InterviewCommandStatus.COMPLETED, "session-1", version,
        status, JobInterviewStage.PROJECT_DEEP_DIVE, "ok", null, null,
        9L, false, List.of());
  }
}
