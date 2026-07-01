package com.linrun.interview.modules.interviewschedule.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview_schedule")
public class InterviewScheduleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String companyName;

    private String position;

    private LocalDateTime interviewTime;

    private String interviewType; // ONSITE, VIDEO, PHONE

    private String meetingLink;

    private Integer roundNumber = 1;

    private String interviewer;

    private String notes;

    private InterviewStatus status = InterviewStatus.PENDING;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;


}
