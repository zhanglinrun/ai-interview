package com.linrun.interview.modules.knowledgebase.rag;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import com.linrun.interview.common.ai.PromptTemplate;
import com.linrun.interview.modules.knowledgebase.mapper.RagChatMessageMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Collections.singletonList;

/**
 * 知识库查询改写器（移植自 know-engine 的 KnowEngineQueryTransformer）。
 *
 * <p>实现 LC4j {@link QueryTransformer}，供 {@code DefaultRetrievalAugmentor} 在检索前改写 query：
 * 用 LLM 结合对话历史把用户原始问题改写成更适合知识库检索的单句查询，缩小问题表述与答案表述的语义鸿沟。
 *
 * <p>与 know-engine 的差异（取精华弃糟粕）：
 * <ul>
 *   <li>改写策略沿用本项目现有的 {@code knowledgebase-query-rewrite.st} 模板（面试领域），不照搬汽车领域 5 策略</li>
 *   <li><b>亮点2</b>：接 {@code progressCallback}，改写前推 {@code 正在优化您的问题...} 进度（null 安全）</li>
 *   <li><b>亮点5</b>：改写完成用虚拟线程异步回写 {@code rag_chat_messages.transform_content}，
 *       repository 由调用方 Spring 注入传入（弃 know-engine 静态 ApplicationContext 反模式）</li>
 *   <li>历史从 {@link Query#metadata()} 的 chatMemory 取，由 RetrievalAugmentor 在组装时注入</li>
 *   <li>关闭/失败/空 query 时返回原 query，保证检索不中断</li>
 * </ul>
 */
@Slf4j
public class InterviewQueryTransformer implements QueryTransformer {

    private static final int MAX_HISTORY_CHARS = 200;
    /** 改写前推给前端的进度文案（不带前缀，前缀由调用方加）。 */
    private static final String PROGRESS_REWRITING = "正在优化您的问题...";

    private final ChatModel chatModel;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean enabled;
    private final Consumer<String> progressCallback;
    private final Long assistantMessageId;
    private final RagChatMessageMapper messageRepository;
    private final RagQueryTrace trace;

    public InterviewQueryTransformer(ChatModel chatModel, PromptTemplate rewritePromptTemplate, boolean enabled) {
        this(chatModel, rewritePromptTemplate, enabled, null, null, null);
    }

    public InterviewQueryTransformer(ChatModel chatModel, PromptTemplate rewritePromptTemplate, boolean enabled,
                                     Consumer<String> progressCallback, Long assistantMessageId,
                                     RagChatMessageMapper messageMapper) {
        this(chatModel, rewritePromptTemplate, enabled, progressCallback, assistantMessageId,
            messageMapper, null);
    }

    public InterviewQueryTransformer(ChatModel chatModel, PromptTemplate rewritePromptTemplate, boolean enabled,
                                     Consumer<String> progressCallback, Long assistantMessageId,
                                     RagChatMessageMapper messageMapper, RagQueryTrace trace) {
        this.chatModel = chatModel;
        this.rewritePromptTemplate = rewritePromptTemplate;
        this.enabled = enabled;
        this.progressCallback = progressCallback;
        this.assistantMessageId = assistantMessageId;
        this.messageRepository = messageMapper;
        this.trace = trace;
    }

    @Override
    public List<Query> transform(Query query) {
        if (!enabled || query == null || query.text() == null || query.text().isBlank()) {
            return singletonList(query);
        }
        if (progressCallback != null) {
            progressCallback.accept(PROGRESS_REWRITING);
        }
        String ruleApplied = InterviewQueryRewriteRules.applyRules(query.text());
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", ruleApplied);
            variables.put("history", formatHistory(query.metadata()));
            String prompt = rewritePromptTemplate.render(variables);
            String rewritten = chatModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build())
                .aiMessage().text();
            if (rewritten == null || rewritten.isBlank()) {
                return buildResultQuery(query, ruleApplied);
            }
            String normalized = rewritten.trim();
            log.info("[InterviewQueryTransformer] 改写: origin='{}', rewritten='{}'",
                query.text(), normalized);
            if (trace != null) {
                trace.rewrittenQuestion(normalized);
            }
            return buildResultQuery(query, normalized);
        } catch (Exception e) {
            log.warn("[InterviewQueryTransformer] 改写失败，使用规则/原问题: {}", e.getMessage(), e);
            return buildResultQuery(query, ruleApplied);
        }
    }

    private List<Query> buildResultQuery(Query query, String text) {
        if (text == null || text.isBlank() || text.equals(query.text())) {
            return singletonList(query);
        }
        if (trace != null) {
            trace.rewrittenQuestion(text.trim());
        }
        persistTransformContent(text.trim());
        Query rewrittenQuery = query.metadata() == null
            ? Query.from(text.trim())
            : Query.from(text.trim(), query.metadata());
        return singletonList(rewrittenQuery);
    }

    /**
     * 虚拟线程异步回写改写结果到 assistant 消息的 transform_content（亮点5）。
     * 失败只 warn，不影响检索主流程。
     */
    private void persistTransformContent(String transformed) {
        if (assistantMessageId == null || messageRepository == null) {
            return;
        }
        final Long msgId = assistantMessageId;
        Thread.ofVirtual().name("query-transform-" + msgId).start(() -> {
            try {
                var msg = messageRepository.selectById(msgId);
                if (msg != null) {
                    msg.setTransformContent(transformed);
                    messageRepository.updateById(msg);
                    log.info("[InterviewQueryTransformer] 改写结果已回写: assistantMsgId={}", msgId);
                }
            } catch (Exception e) {
                log.warn("[InterviewQueryTransformer] 改写结果回写失败: assistantMsgId={}, error={}",
                    msgId, e.getMessage(), e);
            }
        });
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
