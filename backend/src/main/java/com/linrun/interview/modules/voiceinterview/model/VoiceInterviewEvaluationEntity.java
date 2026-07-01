package com.linrun.interview.modules.voiceinterview.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Voice Interview Evaluation Entity
 * 语音面试评估实体
 * <p>
 * Stores evaluation results in a format aligned with text-based interviews:
 * per-question evaluations, overall feedback, strengths, improvements, and reference answers.
 * All structured data (arrays/objects) is stored as JSON TEXT columns.
 * </p>
 */
@TableName("voice_interview_evaluations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewEvaluationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Integer overallScore;

    private String overallFeedback;

    private String questionEvaluationsJson;

    private String strengthsJson;

    private String improvementsJson;

    private String referenceAnswersJson;

    private String interviewerRole;

    private LocalDateTime interviewDate;

    private LocalDateTime createdAt;

}
