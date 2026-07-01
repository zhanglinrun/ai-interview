package com.linrun.interview.modules.voiceinterview.service;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.resume.mapper.ResumeEntityMapper;
import com.linrun.interview.modules.voiceinterview.config.VoiceInterviewProperties;
import com.linrun.interview.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewEvaluationMapper;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewMessageMapper;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewSessionMapper;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VoiceInterviewService 暂停/恢复测试")
class VoiceInterviewServicePauseTest {

    @Mock private VoiceInterviewSessionMapper sessionMapper;
    @Mock private VoiceInterviewMessageMapper messageMapper;
    @Mock private VoiceInterviewEvaluationMapper evaluationMapper;
    @Mock private RedissonClient redissonClient;
    @Mock private VoiceInterviewProperties properties;
    @Mock private VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;
    @Mock private LlmProviderRegistry llmProviderRegistry;
    @Mock private ResumeEntityMapper resumeEntityMapper;
    @Mock private RBucket<VoiceInterviewSessionEntity> bucket;

    @InjectMocks
    private VoiceInterviewService voiceInterviewService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("已完成会话不可暂停")
    void pauseCompletedSessionRejected() {
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(1L)
            .userId(1L)
            .status(VoiceInterviewSessionStatus.COMPLETED)
            .build();
        when(sessionMapper.selectOne(any())).thenReturn(session);

        assertThatThrownBy(() -> voiceInterviewService.pauseSession("1", "user_initiated"))
            .isInstanceOf(BusinessException.class);

        verify(sessionMapper, never()).updateById(any(VoiceInterviewSessionEntity.class));
    }
}
