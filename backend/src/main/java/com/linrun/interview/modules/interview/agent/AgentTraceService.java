package com.linrun.interview.modules.interview.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.modules.interview.agent.model.AgentRunStepEntity;
import com.linrun.interview.modules.interview.agent.model.AgentTraceStep;
import com.linrun.interview.modules.interview.mapper.AgentRunStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 编排轨迹持久化：写入 agent_run_steps，供前端按会话回放决策过程。
 * 轨迹是观测数据，写入失败只告警不阻断出题主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceService {

  private static final int MAX_TEXT_LENGTH = 4000;

  private final AgentRunStepMapper agentRunStepMapper;

  /**
   * 批量落库一次编排产生的轨迹步骤（失败静默降级）。
   */
  public void saveStepsQuietly(String sessionId, Long userId, Integer questionIndex,
                               List<AgentTraceStep> steps) {
    if (steps == null || steps.isEmpty()) {
      return;
    }
    try {
      saveSteps(sessionId, userId, questionIndex, steps);
    } catch (Exception e) {
      log.warn("Agent 轨迹落库失败（不阻断主流程）: sessionId={}, questionIndex={}",
          sessionId, questionIndex, e);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public void saveSteps(String sessionId, Long userId, Integer questionIndex,
                        List<AgentTraceStep> steps) {
    LocalDateTime now = LocalDateTime.now();
    for (AgentTraceStep step : steps) {
      AgentRunStepEntity entity = AgentRunStepEntity.builder()
          .userId(userId)
          .sessionId(sessionId)
          .questionIndex(questionIndex)
          .role(step.role())
          .stepOrder(step.step())
          .action(step.action())
          .actionInput(truncate(step.actionInput()))
          .observation(truncate(step.observation()))
          .createdAt(now)
          .build();
      agentRunStepMapper.insert(entity);
    }
  }

  public List<AgentRunStepEntity> listBySession(String sessionId, Long userId) {
    return agentRunStepMapper.selectList(Wrappers.<AgentRunStepEntity>lambdaQuery()
        .eq(AgentRunStepEntity::getSessionId, sessionId)
        .eq(AgentRunStepEntity::getUserId, userId)
        .orderByAsc(AgentRunStepEntity::getId));
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteBySession(String sessionId) {
    agentRunStepMapper.delete(Wrappers.<AgentRunStepEntity>lambdaQuery()
        .eq(AgentRunStepEntity::getSessionId, sessionId));
  }

  private String truncate(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH) + "…";
  }
}
