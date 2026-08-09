package com.linrun.interview.business.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infra.export.PdfExportService;
import com.linrun.interview.business.converter.InterviewMapper;
import com.linrun.interview.business.entity.InterviewAnswerEntity;
import com.linrun.interview.business.vo.InterviewDetailDTO;
import com.linrun.interview.business.vo.InterviewQuestionDTO;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 面试历史服务
 * 获取面试会话详情和导出面试报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewHistoryService {

    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final InterviewMapper interviewMapper;

    /**
     * 获取面试会话详情
     */
    public InterviewDetailDTO getInterviewDetail(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();
        // answers 是 @TableField(exist=false) 临时字段，selectOne 不会填充，须显式加载，
        // 否则逐题详情全部落到「未回答」分支（得分 0、回答为空）
        session.setAnswers(interviewPersistenceService.findAnswersBySessionId(sessionId));

        // 解析JSON字段
        List<Object> questions = parseJson(session.getQuestionsJson(), new TypeReference<>() {});
        List<String> strengths = parseJson(session.getStrengthsJson(), new TypeReference<>() {});
        List<String> improvements = parseJson(session.getImprovementsJson(), new TypeReference<>() {});
        List<Object> referenceAnswers = parseJson(session.getReferenceAnswersJson(), new TypeReference<>() {});

        // 解析所有题目（用于构建完整的答案列表）
        List<InterviewQuestionDTO> allQuestions = parseJson(
            session.getQuestionsJson(),
                new TypeReference<>() {
                }
        );

        // 构建答案详情列表（包含所有题目，未回答的也要显示）
        List<InterviewDetailDTO.AnswerDetailDTO> answerList = buildAnswerDetailList(
            allQuestions,
            session.getAnswers()
        );

        // 使用 MapStruct 组装最终 DTO
        return interviewMapper.toDetailDTO(
            session,
            questions,
            strengths,
            improvements,
            referenceAnswers,
            answerList
        );
    }

    /**
     * 构建答案详情列表（包含所有题目）
     * 对于用户已回答的题目使用答案数据，对于未回答的题目构建空答案
     */
    private List<InterviewDetailDTO.AnswerDetailDTO> buildAnswerDetailList(
        List<InterviewQuestionDTO> allQuestions,
        List<InterviewAnswerEntity> answers
    ) {
        if (allQuestions == null || allQuestions.isEmpty()) {
            // 如果没有题目数据，回退到仅显示已回答的题目
            return interviewMapper.toAnswerDetailDTOList(answers, this::extractKeyPoints);
        }

        // 将答案按 questionIndex 索引
        Map<Integer, InterviewAnswerEntity> answerMap = answers.stream()
            .collect(Collectors.toMap(
                InterviewAnswerEntity::getQuestionIndex,
                a -> a,
                (a1, a2) -> a1  // 如果有重复，取第一个
            ));

        // 遍历所有题目，构建完整的答案详情列表
        return allQuestions.stream()
            .map(question -> {
                InterviewAnswerEntity answer = answerMap.get(question.questionIndex());
                if (answer != null) {
                    // 用户已回答，使用答案数据
                    return interviewMapper.toAnswerDetailDTO(answer, extractKeyPoints(answer));
                } else {
                    // 用户未回答，构建空答案
                    return new InterviewDetailDTO.AnswerDetailDTO(
                        question.questionIndex(),
                        question.question(),
                        question.category(),
                        null,  // userAnswer
                        question.score() != null ? question.score() : 0,  // score
                        question.feedback(),  // feedback
                        null,  // referenceAnswer
                        null,  // keyPoints
                        null   // answeredAt
                    );
                }
            })
            .toList();
    }

    /**
     * 从 JSON 提取 keyPoints
     */
    private List<String> extractKeyPoints(InterviewAnswerEntity answer) {
        return parseJson(answer.getKeyPointsJson(), new TypeReference<>() {});
    }

    /**
     * 通用 JSON 解析方法
     */
    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.error("解析 JSON 失败", e);
            return null;
        }
    }

    /**
     * 导出面试报告为PDF
     */
    public byte[] exportInterviewPdf(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();
        // 同 getInterviewDetail：answers 需显式加载，否则 PDF 问答详情章节整体缺失
        session.setAnswers(interviewPersistenceService.findAnswersBySessionId(sessionId));
        try {
            return pdfExportService.exportInterviewReport(session);
        } catch (Exception e) {
            log.error("导出PDF失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出面试报告失败", e);
        }
    }
}

