package com.linrun.interview.modules.voiceinterview.service;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.evaluation.EvaluationReport;
import com.linrun.interview.common.evaluation.QaRecord;
import com.linrun.interview.common.evaluation.UnifiedEvaluationService;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.interview.skill.InterviewSkillService;
import com.linrun.interview.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import com.linrun.interview.modules.voiceinterview.dto.VoiceEvaluationDetailDTO.AnswerDetail;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import com.linrun.interview.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewEvaluationMapper;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewMessageMapper;
import com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.mybatis.EntityQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 语音面试评估服务
 * 复用 UnifiedEvaluationService 的分批评估 + 结构化输出 + 降级兜底
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceInterviewEvaluationService {

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewEvaluationMapper evaluationRepository;
    private final VoiceInterviewMessageMapper messageRepository;
    private final VoiceInterviewSessionMapper sessionRepository;
    private final VoiceEvaluationPersistenceService evaluationPersistenceService;
    private final ObjectMapper objectMapper;
    private final InterviewSkillService skillService;

    /**
     * 生成语音面试评估（由异步消费者调用）
     * LLM 调用在事务外执行，仅 DB 写入在事务内（委托 {@link VoiceEvaluationPersistenceService}）。
     * 幂等：已存在评估结果时直接跳过，避免消息重投重复跑 LLM 并撞唯一键把已完成评估打成 FAILED。
     */
    public void generateEvaluation(Long sessionId) {
        try {
            log.info("开始生成语音面试评估: sessionId={}", sessionId);

            if (EntityQueries.selectOne(evaluationRepository,
                    VoiceInterviewEvaluationEntity::getSessionId, sessionId).isPresent()) {
                log.info("语音面试评估已存在，跳过重复评估: sessionId={}", sessionId);
                return;
            }

            VoiceInterviewSessionEntity session = getSession(sessionId);
            List<VoiceInterviewMessageEntity> messages = messageRepository.selectList(
                Wrappers.<VoiceInterviewMessageEntity>lambdaQuery()
                    .eq(VoiceInterviewMessageEntity::getSessionId, sessionId)
                    .orderByAsc(VoiceInterviewMessageEntity::getSequenceNum));

            if (messages.isEmpty()) {
                log.warn("语音面试会话无对话记录，生成空评估结果: sessionId={}", sessionId);
                evaluationPersistenceService.saveEmptyEvaluation(sessionId, session);
                return;
            }

            List<QaRecord> qaRecords = buildQaRecords(messages);

            // 异步语音评估无 UserContext：从会话实体取 userId，走该用户的 BYOK「我的模型」
            ChatModel chatModel = llmProviderRegistry.getUserChatModel(session.getUserId());

            String sessionIdStr = String.valueOf(sessionId);
            String referenceContext = skillService.buildEvaluationReferenceSectionSafe(session.getSkillId());
            EvaluationReport report = unifiedEvaluationService.evaluate(
                chatModel, sessionIdStr, qaRecords, null, referenceContext);

            evaluationPersistenceService.saveEvaluation(sessionId, session, report);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成语音面试评估失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "生成评估失败: " + e.getMessage(), e);
        }
    }

    public VoiceEvaluationDetailDTO getEvaluation(Long sessionId) {
        VoiceInterviewEvaluationEntity evaluation = EntityQueries.selectOne(
            evaluationRepository, VoiceInterviewEvaluationEntity::getSessionId, sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_EVALUATION_NOT_FOUND,
                "评估结果不存在: " + sessionId));

        return buildDetailDTO(evaluation);
    }

    private List<QaRecord> buildQaRecords(List<VoiceInterviewMessageEntity> messages) {
        List<QaRecord> records = new ArrayList<>();
        int index = 0;
        PendingQuestion pendingQuestion = null;

        for (VoiceInterviewMessageEntity msg : messages) {
            String aiText = VoiceInterviewMessageEntity.trimToNull(msg.getAiGeneratedText());
            String userText = VoiceInterviewMessageEntity.trimToNull(msg.getUserRecognizedText());

            if (pendingQuestion != null && userText != null) {
                records.add(new QaRecord(
                    index,
                    pendingQuestion.question(),
                    pendingQuestion.category(),
                    userText
                ));
                index++;
                pendingQuestion = null;
                if (aiText != null) {
                    pendingQuestion = new PendingQuestion(aiText, inferCategory(aiText));
                }
                continue;
            }

            if (pendingQuestion != null) {
                records.add(new QaRecord(
                    index,
                    pendingQuestion.question(),
                    pendingQuestion.category(),
                    null
                ));
                index++;
                pendingQuestion = null;
            }

            if (aiText != null && userText != null) {
                records.add(new QaRecord(index, aiText, inferCategory(aiText), userText));
                index++;
            } else if (aiText != null) {
                pendingQuestion = new PendingQuestion(aiText, inferCategory(aiText));
            } else if (userText != null) {
                records.add(new QaRecord(index, "", "综合", userText));
                index++;
            }
        }

        if (pendingQuestion != null) {
            records.add(new QaRecord(
                index,
                pendingQuestion.question(),
                pendingQuestion.category(),
                null
            ));
        }

        return records;
    }

    private record PendingQuestion(String question, String category) {}

    private String inferCategory(String aiText) {
        if (aiText == null) return "综合";
        if (aiText.contains("项目") || aiText.contains("实习") || aiText.contains("工作经历")) return "项目深挖";
        if (aiText.contains("自我介绍") || aiText.contains("介绍一下自己")) return "自我介绍";
        if (aiText.contains("职业规划") || aiText.contains("为什么") || aiText.contains("优缺点")) return "HR问题";
        return "技术问题";
    }

    private VoiceEvaluationDetailDTO buildDetailDTO(VoiceInterviewEvaluationEntity entity) {
        try {
            List<EvaluationReport.QuestionEvaluation> questionItems = objectMapper.readValue(
                entity.getQuestionEvaluationsJson(),
                new TypeReference<List<EvaluationReport.QuestionEvaluation>>() {}
            );

            List<String> strengths = objectMapper.readValue(
                entity.getStrengthsJson(),
                new TypeReference<List<String>>() {}
            );

            List<String> improvements = objectMapper.readValue(
                entity.getImprovementsJson(),
                new TypeReference<List<String>>() {}
            );

            List<EvaluationReport.ReferenceAnswer> refAnswers = objectMapper.readValue(
                entity.getReferenceAnswersJson(),
                new TypeReference<List<EvaluationReport.ReferenceAnswer>>() {}
            );

            Map<Integer, EvaluationReport.ReferenceAnswer> refMap = refAnswers.stream()
                .collect(Collectors.toMap(
                    EvaluationReport.ReferenceAnswer::questionIndex, r -> r, (a, b) -> a));

            List<AnswerDetail> answers = new ArrayList<>();
            for (EvaluationReport.QuestionEvaluation q : questionItems) {
                EvaluationReport.ReferenceAnswer ref = refMap.get(q.questionIndex());
                answers.add(AnswerDetail.builder()
                    .questionIndex(q.questionIndex())
                    .question(q.question())
                    .category(q.category())
                    .userAnswer(q.userAnswer())
                    .score(q.score())
                    .feedback(q.feedback())
                    .referenceAnswer(ref != null ? ref.referenceAnswer() : null)
                    .keyPoints(ref != null ? ref.keyPoints() : null)
                    .build());
            }

            return VoiceEvaluationDetailDTO.builder()
                .sessionId(entity.getSessionId())
                .totalQuestions(answers.size())
                .overallScore(entity.getOverallScore())
                .overallFeedback(entity.getOverallFeedback())
                .strengths(strengths)
                .improvements(improvements)
                .answers(answers)
                .build();

        } catch (Exception e) {
            log.error("构建评估详情失败: sessionId={}", entity.getSessionId(), e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "构建评估结果失败: " + e.getMessage(), e);
        }
    }

    private VoiceInterviewSessionEntity getSession(Long sessionId) {
        return Optional.ofNullable(sessionRepository.selectById(sessionId))
            .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND,
                "语音面试会话不存在: " + sessionId));
    }
}
