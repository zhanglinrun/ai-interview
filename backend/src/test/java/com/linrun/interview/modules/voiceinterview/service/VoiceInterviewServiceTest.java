package com.linrun.interview.modules.voiceinterview.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.resume.mapper.ResumeEntityMapper;
import com.linrun.interview.modules.voiceinterview.config.VoiceInterviewProperties;
import com.linrun.interview.modules.voiceinterview.dto.CreateSessionRequest;
import com.linrun.interview.modules.voiceinterview.dto.SessionResponseDTO;
import com.linrun.interview.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewEvaluationMapper;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewMessageMapper;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewSessionMapper;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("语音面试服务测试")
class VoiceInterviewServiceTest {

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
        lenient().when(redissonClient.<VoiceInterviewSessionEntity>getBucket(any())).thenReturn(bucket);
        lenient().when(bucket.get()).thenReturn(null);
        stubSessionInsert();
        when(sessionMapper.updateById(any(VoiceInterviewSessionEntity.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Nested
    @DisplayName("会话创建")
    class CreateSessionTests {

        @Test
        @DisplayName("创建会话应持久化并写入缓存")
        void createSession() {
            CreateSessionRequest request = CreateSessionRequest.builder()
                .skillId("ali-p8")
                .introEnabled(true)
                .techEnabled(true)
                .projectEnabled(true)
                .hrEnabled(true)
                .plannedDuration(30)
                .build();

            SessionResponseDTO response = voiceInterviewService.createSession(request);

            assertThat(response.getSessionId()).isEqualTo(1L);
            assertThat(response.getRoleType()).isEqualTo("ali-p8");
            assertThat(response.getCurrentPhase()).isEqualTo("INTRO");
            assertThat(response.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(response.getWebSocketUrl()).isNotBlank();
            verify(sessionMapper, times(1)).insert(any(VoiceInterviewSessionEntity.class));
            verify(bucket, times(1)).set(any(VoiceInterviewSessionEntity.class), any(Duration.class));
        }

        @Test
        @DisplayName("仅启用技术阶段时首阶段应为 TECH")
        void createSessionTechOnly() {
            CreateSessionRequest request = CreateSessionRequest.builder()
                .skillId("byteance-algo")
                .introEnabled(false)
                .techEnabled(true)
                .projectEnabled(false)
                .hrEnabled(false)
                .plannedDuration(15)
                .build();

            SessionResponseDTO response = voiceInterviewService.createSession(request);

            assertThat(response.getCurrentPhase()).isEqualTo("TECH");
        }
    }

    @Nested
    @DisplayName("会话结束")
    class EndSessionTests {

        @Test
        @DisplayName("结束会话应更新状态并触发评估")
        void endSession() {
            Long sessionId = 1L;
            LocalDateTime startTime = LocalDateTime.now().minusMinutes(10);
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                .id(sessionId)
                .userId(USER_ID)
                .roleType("ali-p8")
                .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                .startTime(startTime)
                .plannedDuration(30)
                .build();
            when(sessionMapper.selectById(sessionId)).thenReturn(session);

            voiceInterviewService.endSession(sessionId.toString());

            assertThat(session.getCurrentPhase()).isEqualTo(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
            assertThat(session.getStatus()).isEqualTo(VoiceInterviewSessionStatus.COMPLETED);
            assertThat(session.getEndTime()).isNotNull();
            assertThat(session.getActualDuration()).isNotNull();
            int expectedDuration = (int) Duration.between(startTime, LocalDateTime.now()).toSeconds();
            assertThat(Math.abs(session.getActualDuration() - expectedDuration)).isLessThanOrEqualTo(2);
            verify(sessionMapper, times(1)).updateById(session);
            verify(voiceEvaluateStreamProducer, times(1)).sendEvaluateTask(sessionId.toString());
            verify(bucket, times(1)).delete();
        }

        @Test
        @DisplayName("结束不存在的会话应静默返回")
        void endSessionNotFound() {
            when(sessionMapper.selectById(999L)).thenReturn(null);

            voiceInterviewService.endSession("999");

            verify(sessionMapper, never()).updateById(any(VoiceInterviewSessionEntity.class));
            verify(voiceEvaluateStreamProducer, never()).sendEvaluateTask(any());
        }
    }

    @Nested
    @DisplayName("会话恢复")
    class ResumeSessionTests {

        @Test
        @DisplayName("PAUSED 会话可恢复为 IN_PROGRESS")
        void resumeSession() {
            Long sessionId = 1L;
            VoiceInterviewSessionEntity paused = VoiceInterviewSessionEntity.builder()
                .id(sessionId)
                .userId(USER_ID)
                .roleType("ali-p8")
                .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
                .status(VoiceInterviewSessionStatus.PAUSED)
                .startTime(LocalDateTime.now().minusMinutes(10))
                .plannedDuration(30)
                .build();
            when(sessionMapper.selectOne(any())).thenReturn(paused);
            when(messageMapper.selectCount(any())).thenReturn(2L);

            SessionResponseDTO response = voiceInterviewService.resumeSession(sessionId.toString());

            assertThat(response.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(paused.getResumedAt()).isNotNull();
            verify(sessionMapper).updateById(argThat((VoiceInterviewSessionEntity s) ->
                s.getStatus() == VoiceInterviewSessionStatus.IN_PROGRESS));
        }

        @Test
        @DisplayName("非 PAUSED 状态不可恢复")
        void resumeSessionInvalidStatus() {
            VoiceInterviewSessionEntity inProgress = VoiceInterviewSessionEntity.builder()
                .id(1L)
                .userId(USER_ID)
                .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                .build();
            when(sessionMapper.selectOne(any())).thenReturn(inProgress);

            assertThatThrownBy(() -> voiceInterviewService.resumeSession("1"))
                .isInstanceOf(BusinessException.class);

            verify(sessionMapper, never()).updateById(any(VoiceInterviewSessionEntity.class));
        }
    }

    @Nested
    @DisplayName("会话暂停")
    class PauseSessionTests {

        @Test
        @DisplayName("IN_PROGRESS 会话可暂停")
        void pauseSession() {
            VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                .id(1L)
                .userId(USER_ID)
                .status(VoiceInterviewSessionStatus.IN_PROGRESS)
                .build();
            when(sessionMapper.selectOne(any())).thenReturn(session);

            voiceInterviewService.pauseSession("1", "user_initiated");

            assertThat(session.getStatus()).isEqualTo(VoiceInterviewSessionStatus.PAUSED);
            assertThat(session.getPausedAt()).isNotNull();
            verify(sessionMapper).updateById(session);
        }
    }

    private void stubSessionInsert() {
        when(sessionMapper.insert(any(VoiceInterviewSessionEntity.class))).thenAnswer(invocation -> {
            VoiceInterviewSessionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(1L);
            }
            return 1;
        });
    }
}
