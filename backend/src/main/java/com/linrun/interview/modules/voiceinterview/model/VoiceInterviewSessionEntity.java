package com.linrun.interview.modules.voiceinterview.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import com.linrun.interview.common.model.AsyncTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("voice_interview_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String roleType;

    @Builder.Default
    private String skillId = "java-backend";

    @Builder.Default
    private String difficulty = "mid";

    private String customJdText;

    private Long resumeId;

    @Builder.Default
    private Boolean introEnabled = true;

    @Builder.Default
    private Boolean techEnabled = true;

    @Builder.Default
    private Boolean projectEnabled = true;

    @Builder.Default
    private Boolean hrEnabled = true;

    @Builder.Default
    private String llmProvider = "dashscope";

    private InterviewPhase currentPhase;

    @Builder.Default
    private VoiceInterviewSessionStatus status = VoiceInterviewSessionStatus.IN_PROGRESS;

    @Builder.Default
    private Integer plannedDuration = 30;

    private Integer actualDuration;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime pausedAt;

    private LocalDateTime resumedAt;

    private AsyncTaskStatus evaluateStatus;

    private String evaluateError;



    public enum InterviewPhase {
        INTRO, TECH, PROJECT, HR, COMPLETED
    }
}
