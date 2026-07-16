package com.linrun.interview.modules.algorithm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.modules.algorithm.client.JudgeClientResult;
import com.linrun.interview.modules.algorithm.mapper.CodingAttemptMapper;
import com.linrun.interview.modules.algorithm.mapper.JudgeSubmissionMapper;
import com.linrun.interview.modules.algorithm.model.CodingAttemptEntity;
import com.linrun.interview.modules.algorithm.model.CodingAttemptStatus;
import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import com.linrun.interview.modules.algorithm.model.JudgeStatus;
import com.linrun.interview.modules.algorithm.model.JudgeSubmissionEntity;
import com.linrun.interview.modules.algorithm.model.TestSuiteType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("判题短事务持久化")
class CodingSubmissionPersistenceServiceTest {

  @Mock
  private JudgeSubmissionMapper submissionMapper;
  @Mock
  private CodingAttemptMapper attemptMapper;

  private CodingSubmissionPersistenceService service;

  @BeforeEach
  void setUp() {
    service = new CodingSubmissionPersistenceService(submissionMapper, attemptMapper);
  }

  @Test
  @DisplayName("同一幂等键和相同请求应直接复用既有提交")
  void shouldReuseSameIdempotentSubmission() {
    JudgeSubmissionEntity existing = submission(TestSuiteType.HIDDEN, "hash");
    when(submissionMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    var reservation = service.reserve(
        attempt(), "idem", TestSuiteType.HIDDEN, "source", "hash", 3, "JUDGE0");

    assertThat(reservation.fresh()).isFalse();
    assertThat(reservation.submission()).isSameAs(existing);
    verify(submissionMapper, never()).insert(any(JudgeSubmissionEntity.class));
    verify(attemptMapper, never()).updateById(any(CodingAttemptEntity.class));
  }

  @Test
  @DisplayName("同一幂等键用于不同源码时必须拒绝")
  void shouldRejectIdempotencyKeyReuseForDifferentCode() {
    when(submissionMapper.selectOne(any(Wrapper.class)))
        .thenReturn(submission(TestSuiteType.HIDDEN, "other-hash"));

    assertThatThrownBy(() -> service.reserve(
        attempt(), "idem", TestSuiteType.HIDDEN, "source", "hash", 3, "JUDGE0"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("同一幂等键");
  }

  @Test
  @DisplayName("隐藏提交首次占位应写入源码快照并推进作答状态")
  void shouldReserveFreshHiddenSubmission() {
    when(submissionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    CodingAttemptEntity attempt = attempt();
    when(attemptMapper.updateById(attempt)).thenReturn(1);

    var reservation = service.reserve(
        attempt, "idem", TestSuiteType.HIDDEN, "source", "hash", 3, "JUDGE0");

    assertThat(reservation.fresh()).isTrue();
    ArgumentCaptor<JudgeSubmissionEntity> captor =
        ArgumentCaptor.forClass(JudgeSubmissionEntity.class);
    verify(submissionMapper).insert(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(JudgeStatus.QUEUED);
    assertThat(captor.getValue().getSourceCode()).isEqualTo("source");
    assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("idem");
    assertThat(attempt.getStatus()).isEqualTo(CodingAttemptStatus.SUBMITTED);
    verify(attemptMapper).updateById(attempt);
  }

  @Test
  @DisplayName("隐藏判题通过后应完成作答")
  void shouldCompleteAttemptAfterAcceptedHiddenJudge() {
    JudgeSubmissionEntity entity = submission(TestSuiteType.HIDDEN, "hash");
    when(submissionMapper.selectById(10L)).thenReturn(entity);
    when(submissionMapper.updateById(entity)).thenReturn(1);

    JudgeSubmissionEntity completed = service.complete(10L, new JudgeClientResult(
        "provider-id", JudgeStatus.ACCEPTED, 3, 3, null, 20L, 1024L, null));

    assertThat(completed.getStatus()).isEqualTo(JudgeStatus.ACCEPTED);
    assertThat(completed.getCompletedAt()).isNotNull();
    verify(attemptMapper).updateAfterHiddenJudge(
        eq(8L), eq(7L), eq(CodingAttemptStatus.COMPLETED), any(LocalDateTime.class),
        any(LocalDateTime.class));
  }

  @Test
  @DisplayName("判题服务不可用时保留已提交状态以支持补判")
  void shouldKeepSubmittedAttemptWhenJudgeUnavailable() {
    JudgeSubmissionEntity entity = submission(TestSuiteType.HIDDEN, "hash");
    when(submissionMapper.selectById(10L)).thenReturn(entity);
    when(submissionMapper.updateById(entity)).thenReturn(1);

    service.complete(10L, JudgeClientResult.unavailable(
        3, "JUDGE_UNAVAILABLE", "稍后补判"));

    verify(attemptMapper).updateAfterHiddenJudge(
        eq(8L), eq(7L), eq(CodingAttemptStatus.SUBMITTED), eq(null),
        any(LocalDateTime.class));
  }

  @Test
  @DisplayName("补判占位使用用户条件并提供卡死任务恢复窗口")
  void shouldReserveRejudgeWithStaleCutoff() {
    JudgeSubmissionEntity entity = submission(TestSuiteType.HIDDEN, "hash");
    when(submissionMapper.selectOne(any(Wrapper.class))).thenReturn(entity, entity);
    when(submissionMapper.reserveRejudge(
        eq(10L), eq(7L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

    service.reserveRejudge(7L, "submission-id");

    ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(submissionMapper).reserveRejudge(eq(10L), eq(7L), cutoff.capture(), now.capture());
    assertThat(cutoff.getValue()).isBefore(now.getValue().minusSeconds(119));
  }

  @Test
  @DisplayName("并发补判未抢到状态时应明确冲突")
  void shouldRejectConcurrentRejudge() {
    when(submissionMapper.selectOne(any(Wrapper.class)))
        .thenReturn(submission(TestSuiteType.HIDDEN, "hash"));
    when(submissionMapper.reserveRejudge(anyLong(), anyLong(), any(), any())).thenReturn(0);

    assertThatThrownBy(() -> service.reserveRejudge(7L, "submission-id"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("补判");
  }

  private CodingAttemptEntity attempt() {
    return CodingAttemptEntity.builder()
        .id(8L)
        .attemptId("attempt-id")
        .userId(7L)
        .problemVersionId(6L)
        .language(CodingLanguage.JAVA21)
        .status(CodingAttemptStatus.IN_PROGRESS)
        .lockVersion(0)
        .build();
  }

  private JudgeSubmissionEntity submission(TestSuiteType suiteType, String hash) {
    return JudgeSubmissionEntity.builder()
        .id(10L)
        .submissionId("submission-id")
        .userId(7L)
        .attemptId(8L)
        .idempotencyKey("idem")
        .suiteType(suiteType)
        .language(CodingLanguage.JAVA21)
        .sourceCode("source")
        .codeHash(hash)
        .status(JudgeStatus.QUEUED)
        .passedCount(0)
        .totalCount(3)
        .createdAt(LocalDateTime.now())
        .updatedAt(LocalDateTime.now())
        .lockVersion(0)
        .build();
  }
}
