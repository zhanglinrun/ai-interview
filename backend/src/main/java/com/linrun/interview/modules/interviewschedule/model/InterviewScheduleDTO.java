package com.linrun.interview.modules.interviewschedule.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InterviewScheduleDTO {
    private Long id;
    private String companyName;
    private String position;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime interviewTime;
    private String interviewType;
    private String meetingLink;
    private Integer roundNumber;
    private String interviewer;
    private String notes;
    private InterviewStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
