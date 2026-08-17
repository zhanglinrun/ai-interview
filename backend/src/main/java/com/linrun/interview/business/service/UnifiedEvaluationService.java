package com.linrun.interview.business.service;

import com.linrun.interview.ai.service.PromptTemplate;
import com.linrun.interview.ai.service.StructuredOutputInvoker;
import com.linrun.interview.business.service.EvaluationReport.CategoryScore;
import com.linrun.interview.business.service.EvaluationReport.QuestionEvaluation;
import com.linrun.interview.business.service.EvaluationReport.ReferenceAnswer;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一面试评估服务
 * 分批评估 + 结构化输出 + 二次汇总。批次失败上抛或标未评，不再把空结果写成 0 分成功。
 */
@Service
public class UnifiedEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 6000;

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int evaluationBatchSize;
    private final ResourceLoader resourceLoader;

    record BatchReportDTO(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<QuestionEvalDTO> questionEvaluations
    ) {}

    record QuestionEvalDTO(
        int questionIndex,
        Integer score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints
    ) {}

    record BatchResult(
        int startIndex,
        int endIndex,
        BatchReportDTO report,
        boolean failed
    ) {}

    private record SummaryDTO(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) {}

    public UnifiedEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            ResourceLoader resourceLoader,
            InterviewEvaluationProperties evaluationProperties) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        this.systemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSystemPromptPath()));
        this.userPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getUserPromptPath()));
        this.summarySystemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummarySystemPromptPath()));
        this.summaryUserPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummaryUserPromptPath()));
        this.evaluationBatchSize = Math.max(1, evaluationProperties.getBatchSize());
    }

    public EvaluationReport evaluate(ChatModel chatModel,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText) {
        return evaluate(chatModel, sessionId, qaRecords, resumeText, null);
    }

    public EvaluationReport evaluate(ChatModel chatModel,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText,
                                     String referenceContext) {
        log.info("开始评估面试: sessionId={}, 共{}题", sessionId, qaRecords.size());

        String resumeContext = resumeText != null ? resumeText : "";
        if (resumeContext.length() > 3000) {
            resumeContext = resumeContext.substring(0, 3000) + "\n...(简历内容过长，已截断)";
        }
        String referenceBaseline = referenceContext != null ? referenceContext.trim() : "";
        if (referenceBaseline.length() > MAX_REFERENCE_CONTEXT_CHARS) {
            referenceBaseline = referenceBaseline.substring(0, MAX_REFERENCE_CONTEXT_CHARS)
                + "\n...(参考基线过长，已截断)";
        }

        List<BatchResult> batchResults = evaluateInBatches(
            chatModel, sessionId, resumeContext, qaRecords, referenceBaseline
        );

        List<QuestionEvalDTO> mergedEvaluations = mergeQuestionEvaluations(qaRecords, batchResults);
        String fallbackFeedback = mergeOverallFeedback(batchResults);
        List<String> fallbackStrengths = mergeListItems(batchResults, true);
        List<String> fallbackImprovements = mergeListItems(batchResults, false);

        SummaryDTO summary = summarizeBatchResults(
            chatModel, sessionId, resumeContext, referenceBaseline, qaRecords,
            mergedEvaluations, fallbackFeedback, fallbackStrengths, fallbackImprovements
        );

        return buildReport(sessionId, qaRecords, mergedEvaluations,
            summary.overallFeedback(), summary.strengths(), summary.improvements());
    }

    private String loadPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private List<BatchResult> evaluateInBatches(ChatModel chatModel, String sessionId,
                                                 String resumeContext, List<QaRecord> qaRecords,
                                                 String referenceContext) {
        List<BatchResult> results = new ArrayList<>();
        int failedBatches = 0;
        for (int start = 0; start < qaRecords.size(); start += evaluationBatchSize) {
            int end = Math.min(start + evaluationBatchSize, qaRecords.size());
            List<QaRecord> batch = qaRecords.subList(start, end);
            try {
                BatchReportDTO report = evaluateBatch(
                    chatModel, sessionId, resumeContext, referenceContext, batch);
                if (!isCompleteBatch(report, batch.size())) {
                    log.error("批次评估返回不完整: sessionId={}, expected={}, actual={}",
                        sessionId, batch.size(),
                        report == null || report.questionEvaluations() == null
                            ? 0 : report.questionEvaluations().size());
                    results.add(new BatchResult(start, end, null, true));
                    failedBatches++;
                } else {
                    results.add(new BatchResult(start, end, report, false));
                }
            } catch (Exception e) {
                log.error("批次评估失败: sessionId={}, batchSize={}, error={}",
                    sessionId, batch.size(), e.getMessage(), e);
                results.add(new BatchResult(start, end, null, true));
                failedBatches++;
            }
        }
        if (!results.isEmpty() && failedBatches == results.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "全部评估批次失败，未生成有效评分");
        }
        return results;
    }

    private BatchReportDTO evaluateBatch(ChatModel chatModel, String sessionId,
                                          String resumeContext, String referenceContext,
                                          List<QaRecord> batch) {
        String qaRecords = buildQARecords(batch);
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeContext);
        variables.put("qaRecords", qaRecords);
        variables.put("referenceContext",
            (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
        String userPrompt = userPromptTemplate.render(variables);

        return structuredOutputInvoker.invoke(
            chatModel, systemPrompt, userPrompt, BatchReportDTO.class,
            ErrorCode.INTERVIEW_EVALUATION_FAILED, "批次评估失败：", "批次评估", log
        );
    }

    private boolean isCompleteBatch(BatchReportDTO report, int expectedSize) {
        if (report == null || report.questionEvaluations() == null
            || report.questionEvaluations().size() < expectedSize) {
            return false;
        }
        return report.questionEvaluations().stream()
            .limit(expectedSize)
            .allMatch(eval -> eval != null && eval.score() != null);
    }

    private String buildQARecords(List<QaRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (QaRecord q : batch) {
            sb.append(String.format("问题%d [%s]: %s\n",
                q.questionIndex() + 1, q.category(), q.question()));
            if (Boolean.FALSE.equals(q.criticApproved())) {
                sb.append("【未过审题，评估时降权，不得按正常题均分】\n");
            }
            sb.append(String.format("回答: %s\n\n",
                q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
        }
        return sb.toString();
    }

    private List<QuestionEvalDTO> mergeQuestionEvaluations(List<QaRecord> qaRecords,
                                                           List<BatchResult> batchResults) {
        List<QuestionEvalDTO> merged = new ArrayList<>();
        for (BatchResult result : batchResults) {
            int expectedSize = result.endIndex() - result.startIndex();
            if (result.failed() || result.report() == null
                || result.report().questionEvaluations() == null) {
                for (int i = 0; i < expectedSize; i++) {
                    QaRecord question = qaRecords.get(result.startIndex() + i);
                    merged.add(new QuestionEvalDTO(
                        question.questionIndex(), null,
                        EvaluationQuality.UNSCORED_FEEDBACK, "", List.of()));
                }
                continue;
            }
            List<QuestionEvalDTO> current = result.report().questionEvaluations();
            for (int i = 0; i < expectedSize; i++) {
                QaRecord question = qaRecords.get(result.startIndex() + i);
                QuestionEvalDTO eval = i < current.size() ? current.get(i) : null;
                if (eval == null || eval.score() == null) {
                    merged.add(new QuestionEvalDTO(
                        question.questionIndex(), null,
                        EvaluationQuality.UNSCORED_FEEDBACK, "", List.of()));
                } else {
                    merged.add(eval);
                }
            }
        }
        return merged;
    }

    private String mergeOverallFeedback(List<BatchResult> batchResults) {
        long failed = batchResults.stream().filter(BatchResult::failed).count();
        String feedback = batchResults.stream()
            .filter(result -> !result.failed())
            .map(BatchResult::report)
            .filter(r -> r != null && r.overallFeedback() != null && !r.overallFeedback().isBlank())
            .map(BatchReportDTO::overallFeedback)
            .collect(Collectors.joining("\n\n"));
        if (failed > 0) {
            String prefix = String.format("%d/%d 个评估批次未成功。", failed, batchResults.size());
            return feedback.isBlank() ? prefix : prefix + "\n" + feedback;
        }
        return feedback.isBlank() ? "本次面试已完成分批评估，但未生成有效综合评语。" : feedback;
    }

    private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchResult result : batchResults) {
            if (result.failed()) {
                continue;
            }
            BatchReportDTO report = result.report();
            if (report == null) {
                continue;
            }
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            if (items == null) {
                continue;
            }
            items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .forEach(merged::add);
        }
        return merged.stream().limit(8).toList();
    }

    private SummaryDTO summarizeBatchResults(
            ChatModel chatModel, String sessionId, String resumeContext, String referenceContext,
            List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations,
            String fallbackFeedback, List<String> fallbackStrengths, List<String> fallbackImprovements) {
        try {
            String summarySystem = summarySystemPromptTemplate.render();
            Map<String, Object> vars = new HashMap<>();
            vars.put("resumeText", resumeContext);
            vars.put("referenceContext",
                (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
            vars.put("categorySummary", buildCategorySummary(qaRecords, evaluations));
            vars.put("questionHighlights", buildQuestionHighlights(qaRecords, evaluations));
            vars.put("fallbackOverallFeedback", fallbackFeedback);
            vars.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            vars.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUser = summaryUserPromptTemplate.render(vars);

            SummaryDTO dto = structuredOutputInvoker.invoke(
                chatModel, summarySystem, summaryUser, SummaryDTO.class,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log
            );

            String feedback = dto != null && dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                ? dto.overallFeedback() : fallbackFeedback;
            List<String> strengths = sanitizeItems(dto != null ? dto.strengths() : null, fallbackStrengths);
            List<String> improvements = sanitizeItems(dto != null ? dto.improvements() : null, fallbackImprovements);
            return new SummaryDTO(feedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}",
                sessionId, e.getMessage(), e);
            return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    private List<String> sanitizeItems(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim).distinct().limit(8).toList();
    }

    private EvaluationReport buildReport(String sessionId, List<QaRecord> qaRecords,
                                          List<QuestionEvalDTO> evaluations,
                                          String overallFeedback,
                                          List<String> strengths, List<String> improvements) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> categoryScoresMap = new HashMap<>();

        long answeredCount = qaRecords.stream()
            .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
            .count();

        int evalSize = evaluations != null ? evaluations.size() : 0;
        int scoredCount = 0;

        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evalSize ? evaluations.get(i) : null;
            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            Integer score;
            String feedback;
            if (!hasAnswer) {
                score = 0;
                feedback = eval != null && eval.feedback() != null
                    ? eval.feedback() : "该题未作答。";
            } else if (eval != null && eval.score() != null) {
                score = eval.score();
                feedback = eval.feedback() != null ? eval.feedback() : "";
                scoredCount++;
            } else {
                score = null;
                feedback = eval != null && eval.feedback() != null
                    ? eval.feedback() : EvaluationQuality.UNSCORED_FEEDBACK;
            }
            String refAnswer = eval != null && eval.referenceAnswer() != null
                ? eval.referenceAnswer() : "";
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                ? eval.keyPoints() : List.of();

            questionDetails.add(new QuestionEvaluation(
                q.questionIndex(), q.question(), q.category(), q.userAnswer(), score, feedback
            ));
            referenceAnswers.add(new ReferenceAnswer(
                q.questionIndex(), q.question(), refAnswer, keyPoints
            ));
            if (score != null) {
                categoryScoresMap.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
            }
        }

        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
            .map(e -> new CategoryScore(
                e.getKey(),
                (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                e.getValue().size()
            ))
            .collect(Collectors.toList());

        int totalQuestions = qaRecords.size();
        int overallScore;
        if (answeredCount == 0) {
            overallScore = 0;
        } else {
            double weightedSum = 0;
            double weightTotal = 0;
            for (int i = 0; i < questionDetails.size(); i++) {
                Integer score = questionDetails.get(i).score();
                if (score == null) {
                    continue;
                }
                QaRecord question = qaRecords.get(i);
                double weight = Boolean.FALSE.equals(question.criticApproved()) ? 0.5 : 1.0;
                weightedSum += score * weight;
                weightTotal += weight;
            }
            overallScore = weightTotal == 0 ? 0 : (int) Math.round(weightedSum / weightTotal);
        }

        String feedbackWithRate = overallFeedback;
        if (scoredCount < answeredCount) {
            feedbackWithRate = String.format("已评 %d/%d 题。\n%s", scoredCount, answeredCount,
                overallFeedback != null ? overallFeedback : "");
        } else if (answeredCount < totalQuestions) {
            feedbackWithRate = String.format("作答率 %d/%d。\n%s", answeredCount, totalQuestions,
                overallFeedback != null ? overallFeedback : "");
        }

        return new EvaluationReport(
            sessionId, totalQuestions, (int) answeredCount, overallScore, categoryScores, questionDetails,
            feedbackWithRate,
            strengths != null ? strengths : List.of(),
            improvements != null ? improvements : List.of(),
            referenceAnswers
        );
    }

    private String buildCategorySummary(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        Map<String, List<Integer>> categoryScores = new HashMap<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            if (eval != null && eval.score() != null) {
                categoryScores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(eval.score());
            }
        }
        return categoryScores.entrySet().stream()
            .map(entry -> {
                int avg = (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                return String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(), avg, entry.getValue().size());
            })
            .sorted()
            .collect(Collectors.joining("\n"));
    }

    private String buildQuestionHighlights(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            String scoreText = eval != null && eval.score() != null
                ? String.valueOf(eval.score()) : "未评";
            String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
            String shortQ = q.question().length() > 50 ? q.question().substring(0, 50) + "..." : q.question();
            String shortF = feedback.length() > 80 ? feedback.substring(0, 80) + "..." : feedback;
            highlights.add(String.format("- Q%d | %s | 分数:%s | 反馈:%s",
                q.questionIndex() + 1, shortQ, scoreText, shortF));
        }
        return highlights.stream().limit(20).collect(Collectors.joining("\n"));
    }
}
