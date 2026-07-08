package com.linrun.interview.modules.voiceinterview.integration;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.resume.mapper.ResumeEntityMapper;
import com.linrun.interview.modules.voiceinterview.config.VoiceInterviewProperties;
import com.linrun.interview.modules.voiceinterview.dto.CreateSessionRequest;
import com.linrun.interview.modules.voiceinterview.dto.SessionResponseDTO;
import com.linrun.interview.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewEvaluationMapper;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewMessageMapper;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewSessionMapper;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import com.linrun.interview.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语音面试端到端场景测试（MyBatis-Plus Mapper mock，不依赖 Spring 全量上下文）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("语音面试集成场景测试")
class VoiceInterviewIntegrationTest {

    private static final Long USER_ID = 7L;

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
        UserContext.setUserId(USER_ID);
        VoiceInterviewProperties.PhaseConfig phaseConfig = new VoiceInterviewProperties.PhaseConfig();
        phaseConfig.setIntro(new VoiceInterviewProperties.DurationConfig(3, 5, 8, 2, 5));
        phaseConfig.setTech(new VoiceInterviewProperties.DurationConfig(8, 10, 15, 3, 8));
        phaseConfig.setProject(new VoiceInterviewProperties.DurationConfig(8, 10, 15, 2, 5));
        phaseConfig.setHr(new VoiceInterviewProperties.DurationConfig(3, 5, 8, 2, 5));
        lenient().when(properties.getPhase()).thenReturn(phaseConfig);
        lenient().when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(bucket);
        lenient().when(bucket.get()).thenReturn(null);
        when(sessionMapper.insert(any(VoiceInterviewSessionEntity.class))).thenAnswer(invocation -> {
            VoiceInterviewSessionEntity entity = invocation.getArgument(0);
            entity.setId(100L);
            entity.setStartTime(LocalDateTime.now());
            return 1;
        });
        when(sessionMapper.updateById(any(VoiceInterviewSessionEntity.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("完整流程：创建 → 暂停 → 恢复 → 结束")
    void completeInterviewFlow() {
        CreateSessionRequest createRequest = CreateSessionRequest.builder()
            .skillId("ali-p8")
            .introEnabled(true)
            .techEnabled(true)
            .projectEnabled(true)
            .hrEnabled(true)
            .plannedDuration(30)
            .build();

        SessionResponseDTO created = voiceInterviewService.createSession(createRequest);
        assertThat(created.getSessionId()).isEqualTo(100L);
        assertThat(created.getStatus()).isEqualTo("IN_PROGRESS");

        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(100L)
            .userId(USER_ID)
            .status(VoiceInterviewSessionStatus.IN_PROGRESS)
            .startTime(LocalDateTime.now().minusMinutes(5))
            .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
            .plannedDuration(30)
            .build();
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(sessionMapper.selectById(100L)).thenReturn(session);
        when(messageMapper.selectCount(any())).thenReturn(0L);

        voiceInterviewService.pauseSession("100", "user_initiated");
        assertThat(session.getStatus()).isEqualTo(VoiceInterviewSessionStatus.PAUSED);

        session.setStatus(VoiceInterviewSessionStatus.PAUSED);
        SessionResponseDTO resumed = voiceInterviewService.resumeSession("100");
        assertThat(resumed.getStatus()).isEqualTo("IN_PROGRESS");

        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        voiceInterviewService.endSession("100");
        assertThat(session.getStatus()).isEqualTo(VoiceInterviewSessionStatus.COMPLETED);
        verify(voiceEvaluateStreamProducer).sendEvaluateTask("100");
        verify(bucket, times(2)).set(any(VoiceInterviewSessionEntity.class), any(Duration.class));
    }
}
