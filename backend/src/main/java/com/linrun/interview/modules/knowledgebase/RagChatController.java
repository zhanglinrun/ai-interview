package com.linrun.interview.modules.knowledgebase;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.UpdateKnowledgeBasesRequest;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.UpdateTitleRequest;
import com.linrun.interview.modules.knowledgebase.service.RagChatSessionService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RAG 聊天控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "RAG 问答", description = "基于知识库的智能问答会话")
public class RagChatController {

    static final String EMPTY_RESPONSE_FALLBACK = "本次回答未生成有效内容，请重新提问。";
    static final String CANCELLED_RESPONSE_FALLBACK = "【中断】回答生成已取消，请重新提问。";

    private final RagChatSessionService sessionService;

    /**
     * 创建新会话
     */
    @PostMapping("/api/rag-chat/sessions")
    public Result<SessionDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/api/rag-chat/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(sessionService.listSessions());
    }

    /**
     * 获取会话详情（包含消息历史）
     * GET /api/rag-chat/sessions/{sessionId}
     */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
        return Result.success(sessionService.getSessionDetail(sessionId));
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateTitleRequest request) {
        sessionService.updateSessionTitle(sessionId, request.title());
        return Result.success(null);
    }

    /**
     * 切换会话置顶状态
     * PUT /api/rag-chat/sessions/{sessionId}/pin
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        sessionService.togglePin(sessionId);
        return Result.success(null);
    }

    /**
     * 更新会话知识库
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateSessionKnowledgeBases(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateKnowledgeBasesRequest request) {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds());
        return Result.success(null);
    }

    /**
     * 删除会话
     * DELETE /api/rag-chat/sessions/{sessionId}
     */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success(null);
    }

    /**
     * 发送消息（流式SSE）
     * 流式响应设计：
     * 1. 先同步保存用户消息和创建 AI 消息占位
     * 2. 返回流式响应
     * 3. 流式完成后通过回调更新消息
     */
    @PostMapping(value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request) {

        log.info("收到 RAG 聊天流式请求: sessionId={}, question={}, 线程: {} (虚拟线程: {})",
            sessionId, request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());

        // 1. 准备消息（保存用户消息，创建 AI 消息占位）
        Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

        // 2. 获取流式响应
        StringBuffer fullContent = new StringBuffer();
        AtomicBoolean messagePersisted = new AtomicBoolean(false);
        Flux<String> answerStream;
        try {
            answerStream = sessionService.getStreamAnswer(
                sessionId, request.question(), messageId);
        } catch (Exception e) {
            // getStreamAnswer 在请求线程同步读取会话与历史；异常也转成流错误，统一完成占位消息。
            answerStream = Flux.error(e);
        }

        return answerStream
            .doOnNext(chunk -> {
                // progress:/reference:/citation: 前缀事件是元数据，不计入回答正文；
                // 普通文本是回答 token；交互卡片的提示正文也需要落库，避免刷新后只剩空消息
                if (!isPrefixedEvent(chunk)) {
                    fullContent.append(unescapeChunk(chunk));
                } else if (chunk.startsWith("card:")) {
                    fullContent.append(chunk.substring("card:".length()));
                }
            })
            // 使用 ServerSentEvent 包装；元数据原样透传，回答 token 转义换行避免破坏 SSE
            .map(chunk -> ServerSentEvent.<String>builder()
                .data(isPrefixedEvent(chunk) ? chunk : escapeChunk(chunk))
                .build())
            .doOnComplete(() -> {
                // 3. 流式完成后更新消息内容
                String completedContent = fullContent.toString();
                String content = !completedContent.isBlank()
                    ? completedContent
                    : EMPTY_RESPONSE_FALLBACK;
                completeMessageOnce(messagePersisted, messageId, content);
                log.info("RAG 聊天流式完成: sessionId={}, messageId={}", sessionId, messageId);
                // 4. 异步 LLM 标题生成（亮点6）：首问完成后用虚拟线程根据首问生成摘要标题
                sessionService.maybeGenerateTitleAsync(sessionId, request.question());
            })
            .doOnError(e -> {
                // 错误时也保存已接收的内容
                String content = !fullContent.isEmpty()
                    ? fullContent.toString()
                    : "【错误】回答生成失败：" + e.getMessage();
                completeMessageOnce(messagePersisted, messageId, content);
                log.error("RAG 聊天流式错误: sessionId={}", sessionId, e);
            })
            .doOnCancel(() -> {
                String content = !fullContent.isEmpty()
                    ? fullContent.toString()
                    : CANCELLED_RESPONSE_FALLBACK;
                completeMessageOnce(messagePersisted, messageId, content);
                log.info("RAG 聊天流式取消: sessionId={}, messageId={}", sessionId, messageId);
            });
    }

    private void completeMessageOnce(
        AtomicBoolean messagePersisted, Long messageId, String content) {
        if (messagePersisted.compareAndSet(false, true)) {
            sessionService.completeStreamMessage(messageId, content);
        }
    }

    /** 前缀事件原样透传；除 card: 提示会持久化外，其余事件不计入回答正文。 */
    private static boolean isPrefixedEvent(String chunk) {
        return chunk != null
            && (chunk.startsWith("progress:") || chunk.startsWith("reference:")
            || chunk.startsWith("citation:") || chunk.startsWith("rewritten:")
            || chunk.startsWith("route:")
            || chunk.startsWith("card:") || chunk.startsWith("card_choice:"));
    }

    private static String escapeChunk(String chunk) {
        return chunk.replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescapeChunk(String chunk) {
        return chunk.replace("\\n", "\n").replace("\\r", "\r");
    }
}
