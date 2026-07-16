package com.linrun.interview.modules.algorithm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.modules.algorithm.client.JudgeClient;
import com.linrun.interview.modules.algorithm.client.JudgeClientResult;
import com.linrun.interview.modules.algorithm.dto.SubmitCodeRequest;
import com.linrun.interview.modules.algorithm.mapper.CodingAttemptMapper;
import com.linrun.interview.modules.algorithm.mapper.JudgeSubmissionMapper;
import com.linrun.interview.modules.algorithm.model.CodingAttemptEntity;
import com.linrun.interview.modules.algorithm.model.CodingAttemptStatus;
import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import com.linrun.interview.modules.algorithm.model.CodingProblemVersionEntity;
import com.linrun.interview.modules.algorithm.model.JudgeStatus;
import com.linrun.interview.modules.algorithm.model.JudgeSubmissionEntity;
import com.linrun.interview.modules.algorithm.model.TestSuiteType;
import com.linrun.interview.modules.algorithm.service.CodingSubmissionPersistenceService.SubmissionReservation;
import com.linrun.interview.modules.algorithm.service.TestHarnessFactory.TestHarness;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("判题非事务编排")
class CodingJudgeServiceTest {

  @Mock
  private CodingAttemptService attemptService;
  @Mock
  private AlgorithmCatalogService catalogService;
  @Mock
  private TestHarnessFactory harnessFactory;
  @Mock
  private CodingSubmissionPersistenceService persistenceService;
  @Mock
  private CodingAttemptMapper attemptMapper;
  @Mock
  private JudgeSubmissionMapper submissionMapper;
  @Mock
  private JudgeClient judgeClient;

  private CodingJudgeService service;

  @BeforeEach
  void setUp() {
    service = new CodingJudgeService(
        attemptService, catalogService, harnessFactory, persistenceService,
        attemptMapper, submissionMapper, judgeClient, new FileHashService());
  }

  @Test
  @DisplayName("应先短事务占位、事务外调用 Judge0、再短事务落结果")
  void shouldCallExternalJudgeBetweenPersistenceBoundaries() throws Exception {
    CodingAttemptEntity attempt = attempt();
    CodingProblemVersionEntity version = CodingProblemVersionEntity.builder().id(6L).build();
    JudgeSubmissionEntity queued = submission(JudgeStatus.QUEUED);
    JudgeSubmissionEntity accepted = submission(JudgeStatus.ACCEPTED);
    when(attemptService.requireOwned(7L, "attempt-id")).thenReturn(attempt);
    when(catalogService.requireEnabledVersion(6L, CodingLanguage.JAVA21)).thenReturn(version);
    when(harnessFactory.build(version, CodingLanguage.JAVA21, TestSuiteType.HIDDEN, "source"))
        .thenReturn(new TestHarness("harness", "AIJUDGE_RESULT:3/3", 3));
    when(judgeClient.providerName()).thenReturn("JUDGE0");
    when(persistenceService.reserve(
        eq(attempt), eq("idem"), eq(TestSuiteType.HIDDEN), eq("source"), any(), eq(3),
        eq("JUDGE0"))).thenReturn(new SubmissionReservation(queued, true));
    when(judgeClient.judge(any())).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return new JudgeClientResult(
          "provider", JudgeStatus.ACCEPTED, 3, 3, null, 10L, 1024L, null);
    });
    when(persistenceService.complete(eq(10L), any())).thenReturn(accepted);

    var result = service.submitHidden(
        7L, "attempt-id", new SubmitCodeRequest(" idem ", "source"));

    assertThat(result.status()).isEqualTo(JudgeStatus.ACCEPTED);
    InOrder order = inOrder(persistenceService, judgeClient);
    order.verify(persistenceService).reserve(
        eq(attempt), eq("idem"), eq(TestSuiteType.HIDDEN), eq("source"), any(), eq(3),
        eq("JUDGE0"));
    order.verify(judgeClient).judge(any());
    order.verify(persistenceService).complete(eq(10L), any());
    assertThat(CodingJudgeService.class.getAnnotation(Transactional.class)).isNull();
    assertThat(CodingJudgeService.class
        .getMethod("submitHidden", Long.class, String.class, SubmitCodeRequest.class)
        .getAnnotation(Transactional.class)).isNull();
  }

  @Test
  @DisplayName("命中幂等记录时不得重复调用外部判题")
  void shouldNotJudgeAgainForIdempotentReplay() {
    CodingAttemptEntity attempt = attempt();
    CodingProblemVersionEntity version = CodingProblemVersionEntity.builder().id(6L).build();
    JudgeSubmissionEntity queued = submission(JudgeStatus.QUEUED);
    when(attemptService.requireOwned(7L, "attempt-id")).thenReturn(attempt);
    when(catalogService.requireEnabledVersion(6L, CodingLanguage.JAVA21)).thenReturn(version);
    when(harnessFactory.build(version, CodingLanguage.JAVA21, TestSuiteType.PUBLIC, "source"))
        .thenReturn(new TestHarness("harness", "AIJUDGE_RESULT:2/2", 2));
    when(judgeClient.providerName()).thenReturn("JUDGE0");
    when(persistenceService.reserve(any(), any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new SubmissionReservation(queued, false));

    var result = service.runPublic(
        7L, "attempt-id", new SubmitCodeRequest("idem", "source"));

    assertThat(result.status()).isEqualTo(JudgeStatus.QUEUED);
    verify(judgeClient, never()).judge(any());
    verify(persistenceService, never()).complete(any(), any());
  }

  @Test
  @DisplayName("JudgeClient 异常必须转成 UNAVAILABLE 事实供后续补判")
  void shouldPersistUnavailableWhenClientThrows() {
    CodingAttemptEntity attempt = attempt();
    CodingProblemVersionEntity version = CodingProblemVersionEntity.builder().id(6L).build();
    JudgeSubmissionEntity queued = submission(JudgeStatus.QUEUED);
    JudgeSubmissionEntity unavailable = submission(JudgeStatus.UNAVAILABLE);
    when(attemptService.requireOwned(7L, "attempt-id")).thenReturn(attempt);
    when(catalogService.requireEnabledVersion(6L, CodingLanguage.JAVA21)).thenReturn(version);
    when(harnessFactory.build(version, CodingLanguage.JAVA21, TestSuiteType.PUBLIC, "source"))
        .thenReturn(new TestHarness("harness", "AIJUDGE_RESULT:2/2", 2));
    when(judgeClient.providerName()).thenReturn("JUDGE0");
    when(persistenceService.reserve(any(), any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new SubmissionReservation(queued, true));
    when(judgeClient.judge(any())).thenThrow(new IllegalStateException("network"));
    when(persistenceService.complete(eq(10L), any())).thenReturn(unavailable);

    service.runPublic(7L, "attempt-id", new SubmitCodeRequest("idem", "source"));

    ArgumentCaptor<JudgeClientResult> result = ArgumentCaptor.forClass(JudgeClientResult.class);
    verify(persistenceService).complete(eq(10L), result.capture());
    assertThat(result.getValue().status()).isEqualTo(JudgeStatus.UNAVAILABLE);
    assertThat(result.getValue().failureCode()).isEqualTo("JUDGE_UNAVAILABLE");
  }

  private CodingAttemptEntity attempt() {
    return CodingAttemptEntity.builder()
        .id(8L)
        .attemptId("attempt-id")
        .userId(7L)
        .problemVersionId(6L)
        .language(CodingLanguage.JAVA21)
        .status(CodingAttemptStatus.IN_PROGRESS)
        .build();
  }

  private JudgeSubmissionEntity submission(JudgeStatus status) {
    return JudgeSubmissionEntity.builder()
        .id(10L)
        .submissionId("submission-id")
        .userId(7L)
        .attemptId(8L)
        .suiteType(TestSuiteType.HIDDEN)
        .language(CodingLanguage.JAVA21)
        .status(status)
        .passedCount(status == JudgeStatus.ACCEPTED ? 3 : 0)
        .totalCount(3)
        .submittedAt(LocalDateTime.now())
        .build();
  }
}
