package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.business.entity.AgentRunEntity;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import com.linrun.interview.business.entity.CapabilityEvidenceEntity;
import com.linrun.interview.business.entity.InterviewAnswerEntity;
import com.linrun.interview.business.entity.InterviewCommandEntity;
import com.linrun.interview.business.entity.InterviewSessionEventEntity;
import com.linrun.interview.business.entity.LlmUsageRecordEntity;
import com.linrun.interview.business.mapper.AgentRunMapper;
import com.linrun.interview.business.mapper.AgentRunStepMapper;
import com.linrun.interview.business.mapper.CandidateMemoryMapper;
import com.linrun.interview.business.mapper.CapabilityEvidenceMapper;
import com.linrun.interview.business.mapper.InterviewAnswerMapper;
import com.linrun.interview.business.mapper.InterviewCommandMapper;
import com.linrun.interview.business.mapper.InterviewSessionEventMapper;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.mapper.LlmUsageRecordMapper;
import com.linrun.interview.business.service.CandidateMemoryEntity;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 删除模拟面试会话的附属数据。
 *
 * <p>回答、题目、证据报告、指令、事件和 Agent 轨迹随会话删除；跨会话能力证据与
 * 长期记忆是用户的跨场学习历史，保留内容但解除 session 溯源，避免形成悬空引用。
 *
 * <p>{@code interview_questions} / {@code interview_evidence_reports} 仍对
 * {@code interview_sessions.id} 有外键。岗位实战下线后不再写入这些表，但历史行还在，
 * 不先删从表就删不掉会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobInterviewSessionDeletionService {

  static final String LEFTOVER_CODE_DRAFTS_PROBE_SQL =
      "SELECT 1 FROM interview_code_drafts WHERE 1 = 0";
  static final String LEFTOVER_CODE_DRAFTS_DELETE_SQL =
      "DELETE FROM interview_code_drafts WHERE user_id = ? AND session_id = ?";

  private final InterviewSessionMapper interviewSessionMapper;
  private final InterviewAnswerMapper interviewAnswerMapper;
  private final InterviewCommandMapper commandMapper;
  private final InterviewSessionEventMapper eventMapper;
  private final CapabilityEvidenceMapper capabilityEvidenceMapper;
  private final LlmUsageRecordMapper usageRecordMapper;
  private final AgentRunStepMapper agentRunStepMapper;
  private final AgentRunMapper agentRunMapper;
  private final CandidateMemoryMapper candidateMemoryMapper;
  private final CapabilityProfileService capabilityProfileService;
  private final JdbcTemplate jdbcTemplate;

  private Boolean leftoverCodeDraftsTableExists;

  /** 调用方在同一事务内完成主会话删除；所有条件同时包含用户和会话标识。 */
  @Transactional(rollbackFor = Exception.class)
  public void deleteOwnedSessionArtifacts(Long userId, Long sessionPkId, String sessionId) {
    validate(userId, sessionPkId, sessionId);

    try {
      List<String> affectedAtoms = findAffectedCapabilityAtoms(userId, sessionPkId);
      detachLongTermLearningHistory(userId, sessionPkId, sessionId);
      deleteInterviewRuntimeArtifacts(userId, sessionPkId, sessionId);

      affectedAtoms.forEach(atomId -> capabilityProfileService.refresh(userId, atomId));
      log.info("面试会话附属数据已删除: userId={}, sessionId={}, atoms={}",
          userId, sessionId, affectedAtoms.size());
    } catch (BusinessException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR,
          "删除面试记录失败：" + rootMessage(e), e);
    }
  }

  private List<String> findAffectedCapabilityAtoms(Long userId, Long sessionPkId) {
    return capabilityEvidenceMapper.selectList(Wrappers.<CapabilityEvidenceEntity>lambdaQuery()
            .eq(CapabilityEvidenceEntity::getUserId, userId)
            .eq(CapabilityEvidenceEntity::getSessionId, sessionPkId))
        .stream()
        .map(CapabilityEvidenceEntity::getCapabilityAtomId)
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .toList();
  }

  private void detachLongTermLearningHistory(Long userId, Long sessionPkId, String sessionId) {
    capabilityEvidenceMapper.update(null, Wrappers.<CapabilityEvidenceEntity>lambdaUpdate()
        .eq(CapabilityEvidenceEntity::getUserId, userId)
        .eq(CapabilityEvidenceEntity::getSessionId, sessionPkId)
        .setSql("report_id = NULL, session_id = NULL, question_id = NULL"));

    usageRecordMapper.update(null, Wrappers.<LlmUsageRecordEntity>lambdaUpdate()
        .eq(LlmUsageRecordEntity::getUserId, userId)
        .eq(LlmUsageRecordEntity::getSessionId, sessionId)
        .setSql("session_id = NULL, report_id = NULL"));

    candidateMemoryMapper.update(null, Wrappers.<CandidateMemoryEntity>lambdaUpdate()
        .eq(CandidateMemoryEntity::getUserId, userId)
        .eq(CandidateMemoryEntity::getSessionId, sessionId)
        .setSql("session_id = NULL"));
  }

  private void deleteInterviewRuntimeArtifacts(Long userId, Long sessionPkId, String sessionId) {
    // 外键顺序：解开当前题指针 → 报告 → 代码草稿 → 答案 → 题目 → 会话主记录（由调用方删除）
    interviewSessionMapper.clearCurrentQuestionId(userId, sessionPkId);
    interviewSessionMapper.deleteEvidenceReportsBySession(userId, sessionPkId);
    deleteLeftoverCodeDrafts(userId, sessionPkId);
    interviewAnswerMapper.delete(Wrappers.<InterviewAnswerEntity>lambdaQuery()
        .eq(InterviewAnswerEntity::getUserId, userId)
        .eq(InterviewAnswerEntity::getSessionId, sessionPkId));
    interviewSessionMapper.deleteQuestionsBySession(userId, sessionPkId);
    commandMapper.delete(Wrappers.<InterviewCommandEntity>lambdaQuery()
        .eq(InterviewCommandEntity::getUserId, userId)
        .eq(InterviewCommandEntity::getSessionId, sessionId));
    eventMapper.delete(Wrappers.<InterviewSessionEventEntity>lambdaQuery()
        .eq(InterviewSessionEventEntity::getUserId, userId)
        .eq(InterviewSessionEventEntity::getSessionId, sessionId));
    agentRunStepMapper.delete(Wrappers.<AgentRunStepEntity>lambdaQuery()
        .eq(AgentRunStepEntity::getUserId, userId)
        .eq(AgentRunStepEntity::getSessionId, sessionId));
    if (agentRunMapper != null) {
      agentRunMapper.delete(Wrappers.<AgentRunEntity>lambdaQuery()
          .eq(AgentRunEntity::getUserId, userId)
          .eq(AgentRunEntity::getSessionId, sessionId));
    }
  }

  /**
   * 岗位实战下线后 schema 不再建这张表；已有库仍可能留着并对 session/question 有外键。
   * 探测必须避开 information_schema（Druid wall 会拦），失败时用 savepoint 避免污染删除事务。
   */
  private void deleteLeftoverCodeDrafts(Long userId, Long sessionPkId) {
    if (!leftoverCodeDraftsTableExists()) {
      return;
    }
    jdbcTemplate.update(LEFTOVER_CODE_DRAFTS_DELETE_SQL, userId, sessionPkId);
  }

  private boolean leftoverCodeDraftsTableExists() {
    if (leftoverCodeDraftsTableExists == null) {
      leftoverCodeDraftsTableExists = probeLeftoverCodeDraftsTable();
    }
    return leftoverCodeDraftsTableExists;
  }

  private boolean probeLeftoverCodeDraftsTable() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      var status = TransactionAspectSupport.currentTransactionStatus();
      Object savepoint = status.createSavepoint();
      try {
        jdbcTemplate.queryForList(LEFTOVER_CODE_DRAFTS_PROBE_SQL);
        status.releaseSavepoint(savepoint);
        return true;
      } catch (DataAccessException e) {
        status.rollbackToSavepoint(savepoint);
        log.info("当前库没有残留表 interview_code_drafts，跳过");
        return false;
      }
    }
    try {
      jdbcTemplate.queryForList(LEFTOVER_CODE_DRAFTS_PROBE_SQL);
      return true;
    } catch (DataAccessException e) {
      return false;
    }
  }

  private void validate(Long userId, Long sessionPkId, String sessionId) {
    if (userId == null || userId <= 0 || sessionPkId == null || sessionPkId <= 0
        || sessionId == null || sessionId.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "删除面试会话参数不完整");
    }
  }

  static String rootMessage(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    String message = current.getMessage();
    if (message == null || message.isBlank()) {
      message = error.getMessage();
    }
    return message == null || message.isBlank() ? "请稍后重试" : message;
  }
}
