package interview.guide.modules.knowledgebase.rag;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import interview.guide.common.ai.PromptTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;

/**
 * 知识库查询改写器（移植自 know-engine 的 KnowEngineQueryTransformer）。
 *
 * <p>实现 LC4j {@link QueryTransformer}，供 {@code DefaultRetrievalAugmentor} 在检索前改写 query：
 * 用 LLM 结合对话历史把用户原始问题改写成更适合知识库检索的单句查询，缩小问题表述与答案表述的语义鸿沟。
 *
 * <p>与 know-engine 的差异：
 * <ul>
 *   <li>改写策略沿用本项目现有的 {@code knowledgebase-query-rewrite.st} 模板（面试领域），不照搬汽车领域 5 策略</li>
 *   <li>不回写改写结果到 DB（know-engine 回写 chat_message.transform_content，本项目无此表）</li>
 *   <li>历史从 {@link Query#metadata()} 的 chatMemory 取，由 RetrievalAugmentor 在组装时注入</li>
 *   <li>关闭/失败/空 query 时返回原 query，保证检索不中断</li>
 * </ul>
 */
@Slf4j
public class InterviewQueryTransformer implements QueryTransformer {

    private static final int MAX_HISTORY_CHARS = 200;

    private final ChatModel chatModel;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean enabled;

    public InterviewQueryTransformer(ChatModel chatModel, PromptTemplate rewritePromptTemplate, boolean enabled) {
        this.chatModel = chatModel;
        this.rewritePromptTemplate = rewritePromptTemplate;
        this.enabled = enabled;
    }

    @Override
    public List<Query> transform(Query query) {
        if (!enabled || query == null || query.text() == null || query.text().isBlank()) {
            return singletonList(query);
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", query.text());
            variables.put("history", formatHistory(query.metadata()));
            String prompt = rewritePromptTemplate.render(variables);
            String rewritten = chatModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build())
                .aiMessage().text();
            if (rewritten == null || rewritten.isBlank()) {
                return singletonList(query);
            }
            String normalized = rewritten.trim();
            log.info("[InterviewQueryTransformer] 改写: origin='{}', rewritten='{}'",
                query.text(), normalized);
            Query rewrittenQuery = query.metadata() == null
                ? Query.from(normalized)
                : Query.from(normalized, query.metadata());
            return singletonList(rewrittenQuery);
        } catch (Exception e) {
            log.warn("[InterviewQueryTransformer] 改写失败，使用原问题: {}", e.getMessage(), e);
            return singletonList(query);
        }
    }

    private String formatHistory(Metadata metadata) {
        if (metadata == null) {
            return "";
        }
        List<ChatMessage> chatMemory = metadata.chatMemory();
        if (chatMemory == null || chatMemory.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n对话历史：\n");
        for (ChatMessage msg : chatMemory) {
            if (msg instanceof UserMessage userMessage) {
                sb.append("用户: ").append(truncate(userMessage.singleText())).append("\n");
            } else if (msg instanceof AiMessage aiMessage) {
                sb.append("助手: ").append(truncate(aiMessage.text())).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_HISTORY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_HISTORY_CHARS) + "...";
    }
}
