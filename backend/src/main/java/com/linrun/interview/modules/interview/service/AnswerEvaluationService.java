package com.linrun.interview.modules.interview.service;

import com.linrun.interview.common.evaluation.EvaluationReport;
import com.linrun.interview.common.evaluation.QaRecord;
import com.linrun.interview.common.evaluation.UnifiedEvaluationService;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.interview.model.InterviewQuestionDTO;
import com.linrun.interview.modules.interview.model.InterviewReportDTO;
import com.linrun.interview.modules.interview.model.InterviewReportDTO.CategoryScore;
import com.linrun.interview.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import com.linrun.interview.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文字面试答案评估服务
 * 职责：DTO 适配器，将 InterviewQuestionDTO 转为通用 QaRecord，调用 UnifiedEvaluationService
 */
@Service
public class AnswerEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

    private final UnifiedEvaluationService unifiedEvaluationService;
    public AnswerEvaluationService(UnifiedEvaluationService unifiedEvaluationService) {
        this.unifiedEvaluationService = unifiedEvaluationService;
    }

    /**
     * 评估完整面试并生成报告
     */
    public InterviewReportDTO evaluateInterview(ChatModel chatModel, String sessionId, String resumeText,
                                                 List<InterviewQuestionDTO> questions) {
        log.info("开始评估面试: {}, 共{}题", sessionId, questions.size());

        try {
            // 转为通用问答记录
            List<QaRecord> qaRecords = questions.stream()
                .map(q -> new QaRecord(q.questionIndex(), q.question(), q.category(), q.userAnswer()))
                .toList();

            // 旧文字面试不再注入未经审核的静态 Markdown 答案。岗位实战由版本化 Rubric
            // 与证据快照评估；这里保留通用回答评估，并显式传空参考上下文。
            EvaluationReport report = unifiedEvaluationService.evaluate(
                chatModel, sessionId, qaRecords, resumeText, ""
            );

            // 转为文字面试专用 DTO
            return toInterviewReportDTO(report);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("面试评估失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "面试评估失败：" + e.getMessage(), e);
        }
    }

    private InterviewReportDTO toInterviewReportDTO(EvaluationReport report) {
        return new InterviewReportDTO(
            report.sessionId(),
            report.totalQuestions(),
            report.overallScore(),
            report.categoryScores().stream()
                .map(cs -> new CategoryScore(cs.category(), cs.score(), cs.questionCount()))
                .toList(),
            report.questionDetails().stream()
                .map(qe -> new QuestionEvaluation(qe.questionIndex(), qe.question(), qe.category(),
                    qe.userAnswer(), qe.score(), qe.feedback()))
                .toList(),
            report.overallFeedback(),
            report.strengths(),
            report.improvements(),
            report.referenceAnswers().stream()
                .map(ra -> new ReferenceAnswer(ra.questionIndex(), ra.question(),
                    ra.referenceAnswer(), ra.keyPoints()))
                .toList()
        );
    }
}
