package com.linrun.interview.business.service;

import com.linrun.interview.business.service.InterviewOrchestrator;
import com.linrun.interview.business.vo.InterviewPlan;

/** Agent 面试编排端口：Planner 与逐题状态机共用请求级执行上下文。 */
public interface InterviewOrchestrationPort {
    InterviewPlan plan(InterviewOrchestrator.PlanRequest request);
    InterviewOrchestrator.GeneratedQuestion nextQuestion(InterviewOrchestrator.NextQuestionRequest request);
}
