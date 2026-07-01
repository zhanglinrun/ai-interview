package com.linrun.interview.modules.interviewschedule.service;

import com.linrun.interview.modules.interviewschedule.model.InterviewStatus;
import com.linrun.interview.modules.interviewschedule.mapper.InterviewScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleStatusUpdater {

    private final InterviewScheduleMapper repository;

    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void updateExpiredInterviews() {
        int updated = repository.updateStatusByStatusAndInterviewTimeBefore(
            InterviewStatus.CANCELLED.name(), InterviewStatus.PENDING.name(), LocalDateTime.now());

        if (updated > 0) {
            log.info("已将 {} 条过期面试标记为已取消", updated);
        }
    }
}
