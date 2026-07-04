package com.linrun.interview.modules.mcp.tool;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.evaluation.EvaluationReport;
import com.linrun.interview.common.evaluation.QaRecord;
import com.linrun.interview.common.evaluation.UnifiedEvaluationService;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.UUID;

/**
 * MCP 工具：面试回答即席评估。委托 {@link UnifiedEvaluationService}
 * （与文字/语音面试评估共用同一套分批评估 + 结构化输出 + 降级兜底逻辑）。
 */
@Slf4j
@RequiredArgsConstructor
public class EvaluationMcpTool {

    private static final String QUESTION_CATEGORY = "MCP即席评估";

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final LlmProviderRegistry llmProviderRegistry;

    public record AnswerEvaluation(
        Integer score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints,
        String message
    ) {
        static AnswerEvaluation error(String message) {
            return new AnswerEvaluation(null, null, null, List.of(), message);
        }
    }

    @Tool(name = "evaluate_answer", description = "对一道面试题的候选人回答做 AI 评分："
        + "返回 0-100 分、针对性反馈、参考答案与关键考点。")
    public AnswerEvaluation evaluateAnswer(
            @ToolParam(description = "面试题目") String question,
            @ToolParam(description = "候选人的回答内容") String answer) {
        if (question == null || question.isBlank()) {
            return AnswerEvaluation.error("question 不能为空");
        }
        if (answer == null || answer.isBlank()) {
            return AnswerEvaluation.error("answer 不能为空");
        }

        String sessionId = "mcp-" + UUID.randomUUID();
        try {
            ChatModel chatModel = llmProviderRegistry.getChatModelOrDefault(null);
            EvaluationReport report = unifiedEvaluationService.evaluate(
                chatModel, sessionId,
                List.of(new QaRecord(1, question.trim(), QUESTION_CATEGORY, answer.trim())),
                null);
            return toEvaluation(report);
        } catch (Exception e) {
            log.warn("[McpTool] evaluate_answer 评估失败: sessionId={}", sessionId, e);
            return AnswerEvaluation.error("评估失败: " + e.getMessage());
        }
    }

    private AnswerEvaluation toEvaluation(EvaluationReport report) {
        EvaluationReport.QuestionEvaluation detail =
            report.questionDetails().isEmpty() ? null : report.questionDetails().get(0);
        EvaluationReport.ReferenceAnswer reference =
            report.referenceAnswers().isEmpty() ? null : report.referenceAnswers().get(0);
        return new AnswerEvaluation(
            detail != null ? detail.score() : report.overallScore(),
            detail != null ? detail.feedback() : report.overallFeedback(),
            reference != null ? reference.referenceAnswer() : null,
            reference != null ? reference.keyPoints() : List.of(),
            null);
    }
}
