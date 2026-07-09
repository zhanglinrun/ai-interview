package com.linrun.interview.modules.voiceinterview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.evaluation.EvaluationReport;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewEvaluationMapper;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 语音面试评估落库（独立事务边界）。
 *
 * <p>从 {@link VoiceInterviewEvaluationService} 拆出：原先 {@code @Transactional} 保存方法被同类
 * {@code this} 调用，AOP 代理不生效（违反 AGENTS.md）。拆成独立 bean 后事务注解真正生效。
 *
 * <p>保存一律走「按 sessionId 查已存或新建」的 upsert，避免消息重投时纯 INSERT 撞
 * {@code uk_voice_eval_session} 唯一键、把已成功的评估结果打成 FAILED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceEvaluationPersistenceService {

    private final VoiceInterviewEvaluationMapper evaluationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveEvaluation(Long sessionId, VoiceInterviewSessionEntity session,
                               EvaluationReport report) {
        try {
            VoiceInterviewEvaluationEntity entity = EntityQueries.selectOne(
                    evaluationRepository, VoiceInterviewEvaluationEntity::getSessionId, sessionId)
                .orElseGet(() -> VoiceInterviewEvaluationEntity.builder().sessionId(sessionId).build());

            entity.setOverallScore(report.overallScore());
            entity.setOverallFeedback(report.overallFeedback());
            entity.setQuestionEvaluationsJson(objectMapper.writeValueAsString(report.questionDetails()));
            entity.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
            entity.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
            entity.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
            entity.setInterviewerRole(session.getRoleType());
            entity.setInterviewDate(session.getStartTime());

            MapperUtils.save(evaluationRepository, entity);
            log.info("评估结果已保存: sessionId={}, score={}", sessionId, entity.getOverallScore());
        } catch (Exception e) {
            log.error("保存评估结果失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "保存评估失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void saveEmptyEvaluation(Long sessionId, VoiceInterviewSessionEntity session) {
        try {
            VoiceInterviewEvaluationEntity entity = EntityQueries.selectOne(
                    evaluationRepository, VoiceInterviewEvaluationEntity::getSessionId, sessionId)
                .orElseGet(() -> VoiceInterviewEvaluationEntity.builder().sessionId(sessionId).build());

            entity.setOverallScore(0);
            entity.setOverallFeedback("本次语音面试未形成有效对话记录，暂无可评估内容。");
            entity.setQuestionEvaluationsJson("[]");
            entity.setStrengthsJson("[]");
            entity.setImprovementsJson("[\"请先完成至少一轮有效问答后再生成评估。\"]");
            entity.setReferenceAnswersJson("[]");
            entity.setInterviewerRole(session.getRoleType());
            entity.setInterviewDate(session.getStartTime());

            MapperUtils.save(evaluationRepository, entity);
            log.info("空评估结果已保存: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("保存空评估结果失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "保存空评估失败: " + e.getMessage(), e);
        }
    }
}
