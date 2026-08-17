package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.mapper.InterviewAnswerMapper;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.service.JobInterviewSessionDeletionService;
import com.linrun.interview.business.mapper.ResumeEntityMapper;
import java.util.Arrays;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("旧面试会话删除入口")
class InterviewPersistenceDeletionTest {

  @Mock
  private InterviewSessionMapper sessionMapper;
  @Mock
  private InterviewAnswerMapper answerMapper;
  @Mock
  private ResumeEntityMapper resumeMapper;
  @Mock
  private ObjectMapper objectMapper;
  @Mock
  private JobInterviewSessionDeletionService deletionService;

  private InterviewPersistenceService service;

  @BeforeEach
  void setUp() {
    UserContext.setUserId(7L);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "interview-deletion-test"),
        InterviewSessionEntity.class);
    service = new InterviewPersistenceService(
        sessionMapper, answerMapper, resumeMapper, objectMapper, deletionService);
  }

  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  @Test
  @DisplayName("只删除当前用户会话并在主记录前清理附属数据")
  void shouldDeleteOwnedArtifactsBeforeSession() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setId(11L);
    session.setUserId(7L);
    session.setSessionId("session-1");
    when(sessionMapper.selectOne(any())).thenReturn(session);

    service.deleteSessionBySessionId("session-1");

    InOrder order = inOrder(deletionService, sessionMapper);
    order.verify(deletionService).deleteOwnedSessionArtifacts(7L, 11L, "session-1");
    order.verify(sessionMapper).delete(argThat(wrapper -> hasParams(wrapper, 7L, 11L)));
    verify(sessionMapper).selectOne(argThat(
        wrapper -> hasParams(wrapper, 7L, "session-1")));
  }

  @Test
  @DisplayName("其他用户会话不可见时不触发任何删除")
  void shouldNotDeleteUnownedSession() {
    when(sessionMapper.selectOne(any())).thenReturn(null);

    assertThatThrownBy(() -> service.deleteSessionBySessionId("other-session"))
        .isInstanceOf(BusinessException.class);

    verify(deletionService, never()).deleteOwnedSessionArtifacts(any(), any(), any());
    verify(sessionMapper, never()).delete(any());
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
