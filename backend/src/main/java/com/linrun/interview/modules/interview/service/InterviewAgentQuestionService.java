package com.linrun.interview.modules.interview.service;

import com.linrun.interview.modules.interview.agent.InterviewAgentLoop;
import com.linrun.interview.modules.interview.agent.model.InterviewAgentResult;
import com.linrun.interview.modules.interview.agent.tool.AgentToolContext;
import com.linrun.interview.modules.interview.model.HistoricalQuestion;
import com.linrun.interview.modules.interview.model.InterviewQuestionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ???? Agent + ???????????
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewAgentQuestionService {

    private static final String AGENT_QUESTION_TYPE = "KB_AGENT";
    private static final String AGENT_QUESTION_CATEGORY = "?????";

    private final InterviewAgentLoop agentLoop;

    public List<InterviewQuestionDTO> generateQuestions(
            String llmProvider,
            AgentToolContext context,
            int questionCount,
            List<HistoricalQuestion> historicalQuestions) {
        if (questionCount <= 0) {
            return List.of();
        }

        List<InterviewQuestionDTO> questions = new ArrayList<>(questionCount);
        for (int index = 0; index < questionCount; index++) {
            String conversationLog = buildConversationLog(questions, historicalQuestions);
            InterviewAgentResult result = agentLoop.run(llmProvider, context, conversationLog);
            InterviewQuestionDTO question = InterviewQuestionDTO.create(
                index,
                result.question(),
                AGENT_QUESTION_TYPE,
                AGENT_QUESTION_CATEGORY,
                truncateRationale(result.rationale()),
                result.isFollowUp(),
                result.isFollowUp() && index > 0 ? index - 1 : null
            );
            questions.add(question);
            log.info("Agent knowledge-base question: index={}, followUp={}, rounds={}",
                index, result.isFollowUp(), result.rounds());
        }
        return questions;
    }

    private String buildConversationLog(List<InterviewQuestionDTO> generated,
                                        List<HistoricalQuestion> historicalQuestions) {
        StringBuilder sb = new StringBuilder();
        if (historicalQuestions != null && !historicalQuestions.isEmpty()) {
            sb.append("???????????????????\n");
            for (HistoricalQuestion historical : historicalQuestions) {
                if (historical.question() != null && !historical.question().isBlank()) {
                    sb.append("- ").append(historical.question().trim()).append('\n');
                }
            }
            sb.append('\n');
        }
        if (generated.isEmpty()) {
            return sb.toString();
        }
        sb.append("??????????\n");
        for (InterviewQuestionDTO question : generated) {
            sb.append("???: ").append(question.question()).append('\n');
        }
        return sb.toString();
    }

    private String truncateRationale(String rationale) {
        if (rationale == null || rationale.isBlank()) {
            return null;
        }
        String normalized = rationale.trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }
}
