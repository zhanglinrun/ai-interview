package com.linrun.interview.modules.voiceinterview.repository;

import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionEntity.InterviewPhase;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 语音面试会话Repository
 */
@Repository
public interface VoiceInterviewSessionRepository extends JpaRepository<VoiceInterviewSessionEntity, Long> {

    /**
     * 根据用户ID查找所有会话，按开始时间倒序
     */
    List<VoiceInterviewSessionEntity> findByUserIdOrderByStartTimeDesc(Long userId);

    Optional<VoiceInterviewSessionEntity> findByUserIdAndId(Long userId, Long id);

    boolean existsByUserIdAndId(Long userId, Long id);

    /**
     * 查找指定状态且结束时间早于给定时间的会话
     * Note: Queries the AsyncTaskStatus field, not InterviewPhase
     */
    Optional<VoiceInterviewSessionEntity> findByStatusAndEndTimeBefore(
        com.linrun.interview.common.model.AsyncTaskStatus status,
        LocalDateTime time
    );

    /**
     * Find all sessions for a user, ordered by update time
     */
    List<VoiceInterviewSessionEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * Find sessions by user and status, ordered by update time
     */
    List<VoiceInterviewSessionEntity> findByUserIdAndStatusOrderByUpdatedAtDesc(
        Long userId,
        VoiceInterviewSessionStatus status
    );

    List<VoiceInterviewSessionEntity> findByStatusAndStartTimeBefore(
        VoiceInterviewSessionStatus status,
        LocalDateTime time
    );

    List<VoiceInterviewSessionEntity> findByEvaluateStatusAndUpdatedAtBefore(
        com.linrun.interview.common.model.AsyncTaskStatus evaluateStatus,
        LocalDateTime time
    );
}
