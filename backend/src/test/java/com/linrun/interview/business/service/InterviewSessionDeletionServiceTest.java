package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linrun.interview.business.entity.AgentRunEntity;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.entity.CapabilityEvidenceEntity;
import com.linrun.interview.business.entity.InterviewAnswerEntity;
import com.linrun.interview.business.entity.InterviewCommandEntity;
import com.linrun.interview.business.entity.InterviewSessionEventEntity;
import com.linrun.interview.business.entity.LlmUsageRecordEntity;
import com.linrun.interview.business.mapper.AgentRunMapper;
import com.linrun.interview.business.mapper.AgentRunStepMapper;
import com.linrun.interview.business.mapper.CandidateMemoryMapper;
import com.linrun.interview.business.mapper.CapabilityEvidenceMapper;
import com.linrun.interview.business.mapper.InterviewAnswerMapper;
import com.linrun.interview.business.mapper.InterviewCommandMapper;
import com.linrun.interview.business.mapper.InterviewSessionEventMapper;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.mapper.LlmUsageRecordMapper;
import com.linrun.interview.common.exception.BusinessException;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试会话附属数据删除")
class InterviewSessionDeletionServiceTest {

  @Mock
  private InterviewSessionMapper interviewSessionMapper;
  @Mock
  private InterviewAnswerMapper interviewAnswerMapper;
  @Mock
  private InterviewCommandMapper commandMapper;
  @Mock
  private InterviewSessionEventMapper eventMapper;
  @Mock
  private CapabilityEvidenceMapper capabilityEvidenceMapper;
  @Mock
  private LlmUsageRecordMapper usageRecordMapper;
  @Mock
  private AgentRunStepMapper agentRunStepMapper;
  @Mock
  private AgentRunMapper agentRunMapper;
  @Mock
  private CandidateMemoryMapper candidateMemoryMapper;
  @Mock
  private CapabilityProfileService capabilityProfileService;
  @InjectMocks
  private InterviewSessionDeletionService service;

  @BeforeEach
  void initializeMybatisMetadata() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(
        new MybatisConfiguration(), "interview-deletion-test");
    List.of(
        InterviewAnswerEntity.class,
        InterviewCommandEntity.class,
        InterviewSessionEventEntity.class,
        CapabilityEvidenceEntity.class,
        LlmUsageRecordEntity.class,
        AgentRunStepEntity.class,
        AgentRunEntity.class,
        CandidateMemoryEntity.class)
        .forEach(entity -> TableInfoHelper.initTableInfo(assistant, entity));
  }

  @Test
  @DisplayName("先解除长期学习溯源，再按外键顺序删除报告、答案和题目")
  void shouldDeleteForeignKeyChildrenBeforeSessionOwnedArtifacts() {
    CapabilityEvidenceEntity evidence = new CapabilityEvidenceEntity();
    evidence.setCapabilityAtomId("java.concurrent");
    when(capabilityEvidenceMapper.selectList(any())).thenReturn(List.of(evidence));
    service.deleteOwnedSessionArtifacts(7L, 11L, "session-1");

    InOrder order = inOrder(
        capabilityEvidenceMapper, usageRecordMapper, candidateMemoryMapper,
        interviewSessionMapper, interviewAnswerMapper,
        commandMapper, eventMapper, agentRunStepMapper, agentRunMapper,
        capabilityProfileService);
    order.verify(capabilityEvidenceMapper).update(isNull(), any());
    order.verify(usageRecordMapper).update(isNull(), any());
    order.verify(candidateMemoryMapper).update(isNull(), any());
    order.verify(interviewSessionMapper).clearCurrentQuestionId(7L, 11L);
    order.verify(interviewSessionMapper).deleteEvidenceReportsBySession(7L, 11L);
    order.verify(interviewAnswerMapper).delete(any());
    order.verify(interviewSessionMapper).deleteQuestionsBySession(7L, 11L);
    order.verify(commandMapper).delete(any());
    order.verify(eventMapper).delete(any());
    order.verify(agentRunStepMapper).delete(any());
    order.verify(agentRunMapper).delete(any());
    order.verify(capabilityProfileService).refresh(7L, "java.concurrent");

    verify(capabilityEvidenceMapper, never()).delete(any());
    verify(usageRecordMapper, never()).delete(any());
    verify(candidateMemoryMapper, never()).delete(any());
  }

  @Test
  @DisplayName("参数不完整时不执行任何删除")
  void shouldRejectIncompleteDeletionScope() {
    assertThatThrownBy(() -> service.deleteOwnedSessionArtifacts(7L, null, "session-1"))
        .isInstanceOf(BusinessException.class);

    verify(interviewSessionMapper, never()).deleteEvidenceReportsBySession(any(), any());
    verify(interviewAnswerMapper, never()).delete(any());
  }
}
