package com.linrun.interview.business.service;

import com.linrun.interview.business.vo.InterviewPlan;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Planner Agent：面试开始前产出结构化大纲（主题/难度曲线/简历与 JD 侧重点）。
 *
 * <p>无工具、无记忆的单轮结构化输出；简历摘要、知识库资料要点、候选人历史画像
 * 由编排器在请求线程预先组装进 user message（工具依赖 UserContext ThreadLocal，
 * 统一由编排器收集可避免 Planner 侧的工具调用不确定性）。
 */
public interface PlannerAiService {

  @SystemMessage(fromResource = "/prompts/agent/planner-system.txt")
  InterviewPlan plan(@UserMessage String planningRequest);
}
