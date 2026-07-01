package com.linrun.interview.modules.interview.service;

import com.linrun.interview.modules.interview.agent.InterviewAgentLoop;
import com.linrun.interview.modules.interview.agent.model.AgentTraceStep;
import com.linrun.interview.modules.interview.agent.model.InterviewAgentResult;
import com.linrun.interview.modules.interview.agent.tool.AgentToolContext;
import com.linrun.interview.modules.interview.model.HistoricalQuestion;
import com.linrun.interview.modules.interview.model.InterviewQuestionDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InterviewAgentQuestionService 测试")
class InterviewAgentQuestionServiceTest {

    @Mock
    private InterviewAgentLoop agentLoop;

    @InjectMocks
    private InterviewAgentQuestionService service;

    @Test
    @DisplayName("应按题目数量多次调用 Agent 并映射为 InterviewQuestionDTO")
    void shouldGenerateQuestionsViaAgentLoop() {
        AgentToolContext context = new AgentToolContext("java-backend", "mid", 1L, List.of(10L, 11L));
        when(agentLoop.run(eq("dashscope"), eq(context), anyString()))
            .thenReturn(new InterviewAgentResult("第一题", "理由1", false, List.of(), 1))
            .thenReturn(new InterviewAgentResult("第二题", "理由2", true, List.of(new AgentTraceStep(
                1, "", "searchKnowledgeBase", "{}", "命中片段")), 2));

        List<InterviewQuestionDTO> questions = service.generateQuestions(
            "dashscope", context, 2, List.of(new HistoricalQuestion("旧题", "JAVA", "Redis")));

        assertThat(questions).hasSize(2);
        assertThat(questions.get(0).question()).isEqualTo("第一题");
        assertThat(questions.get(1).question()).isEqualTo("第二题");
        assertThat(questions.get(1).isFollowUp()).isTrue();
        assertThat(questions.get(1).parentQuestionIndex()).isEqualTo(0);
        verify(agentLoop, times(2)).run(eq("dashscope"), eq(context), anyString());
    }
}
