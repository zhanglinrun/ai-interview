package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.entity.InterviewAnswerEntity;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.entity.ResumeEntity;
import com.linrun.interview.business.mapper.InterviewAnswerMapper;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.mapper.ResumeEntityMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("恢复面试会话时挂载简历")
class InterviewPersistenceResumeAttachTest {

  @Mock
  private InterviewSessionMapper sessionMapper;
  @Mock
  private InterviewAnswerMapper answerMapper;
  @Mock
  private ResumeEntityMapper resumeMapper;
  @Mock
  private JobInterviewSessionDeletionService deletionService;

  private InterviewPersistenceService service;

  @BeforeEach
  void setUp() {
    UserContext.setUserId(7L);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "resume-attach-session-test"),
        InterviewSessionEntity.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "resume-attach-answer-test"),
        InterviewAnswerEntity.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), "resume-attach-resume-test"),
        ResumeEntity.class);
    service = new InterviewPersistenceService(
        sessionMapper, answerMapper, resumeMapper, new ObjectMapper(), deletionService);
  }

  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  @Test
  @DisplayName("findBySessionId 挂上当前用户简历正文")
  void findBySessionIdAttachesOwnedResume() {
    InterviewSessionEntity session = session("text-session");
    session.setResumeId(42L);
    when(sessionMapper.selectOne(any())).thenReturn(session);
    when(resumeMapper.selectOne(any())).thenReturn(resume(42L, "做过 Redis 缓存"));

    assertThat(service.findBySessionId("text-session"))
        .isPresent()
        .get()
        .extracting(item -> item.getResume().getResumeText())
        .isEqualTo("做过 Redis 缓存");
    assertThat(session.getResumeId()).isEqualTo(42L);
  }

  @Test
  @DisplayName("简历已删时保留 resumeId，不把外键清掉")
  void missingResumeKeepsResumeId() {
    InterviewSessionEntity session = session("text-session");
    session.setResumeId(42L);
    when(sessionMapper.selectOne(any())).thenReturn(session);
    when(resumeMapper.selectOne(any())).thenReturn(null);

    assertThat(service.findBySessionId("text-session"))
        .isPresent()
        .get()
        .satisfies(item -> {
          assertThat(item.getResume()).isNull();
          assertThat(item.getResumeId()).isEqualTo(42L);
        });
  }

  @Test
  @DisplayName("未完成会话恢复同样挂简历")
  void findUnfinishedSessionAttachesResume() {
    InterviewSessionEntity session = session("open-session");
    session.setResumeId(42L);
    session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    when(sessionMapper.selectOne(any())).thenReturn(session);
    when(resumeMapper.selectOne(any())).thenReturn(resume(42L, "Spring 项目"));

    assertThat(service.findUnfinishedSession(42L))
        .isPresent()
        .get()
        .extracting(item -> item.getResume().getResumeText())
        .isEqualTo("Spring 项目");
  }

  private InterviewSessionEntity session(String sessionId) {
    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setId(11L);
    entity.setUserId(7L);
    entity.setSessionId(sessionId);
    entity.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
    return entity;
  }

  private ResumeEntity resume(Long id, String text) {
    ResumeEntity entity = new ResumeEntity();
    entity.setId(id);
    entity.setUserId(7L);
    entity.setResumeText(text);
    return entity;
  }
}
