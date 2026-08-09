package com.linrun.interview.business.service;

import com.linrun.interview.business.vo.CreateInterviewRequest;
import com.linrun.interview.business.vo.InterviewSessionDTO;
import com.linrun.interview.business.vo.SubmitAnswerRequest;
import com.linrun.interview.business.vo.SubmitAnswerResponse;

/** 统一面试会话生命周期端口，Controller 不直接依赖缓存或持久化实现。 */
public interface InterviewSessionLifecycle {
    InterviewSessionDTO createSession(CreateInterviewRequest request);
    InterviewSessionDTO getSession(String sessionId);
    SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request);
    void completeInterview(String sessionId);
}
