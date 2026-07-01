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

@TableName("voice_interview_messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String messageType; // USER_SPEECH, AI_SPEECH, SYSTEM

    private VoiceInterviewSessionEntity.InterviewPhase phase;

    private String userRecognizedText;

    private String aiGeneratedText;

    private LocalDateTime timestamp;

    private Integer sequenceNum;

    private LocalDateTime createdAt;


    public static String trimToNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }
}
