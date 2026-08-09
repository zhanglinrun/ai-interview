package com.linrun.interview.chat.controller;
import com.linrun.interview.chat.dto.RagChatDTO;
import com.linrun.interview.chat.dto.SseEventEnvelope;


import com.linrun.interview.common.result.Result;
import com.linrun.interview.chat.dto.RagChatDTO.CreateSessionRequest;
import com.linrun.interview.chat.dto.RagChatDTO.SendMessageRequest;
import com.linrun.interview.chat.dto.RagChatDTO.SessionDTO;
import com.linrun.interview.chat.dto.RagChatDTO.SessionDetailDTO;
import com.linrun.interview.chat.dto.RagChatDTO.SessionListItemDTO;
import com.linrun.interview.chat.dto.RagChatDTO.UpdateKnowledgeBasesRequest;
import com.linrun.interview.chat.dto.RagChatDTO.UpdateTitleRequest;
import com.linrun.interview.chat.service.RagChatSessionService;
import com.linrun.interview.chat.service.TraceIdService;
import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG 聊天控制器
 */
@Slf4j
@RestController
@Tag(name = "RAG 问答", description = "基于知识库的智能问答会话")
@RequestMapping("/api/v1/chat")
public class RagChatController {

    static final String EMPTY_RESPONSE_FALLBACK = "本次回答未生成有效内容，请重新提问。";
    static final String CANCELLED_RESPONSE_FALLBACK = "【中断】回答生成已取消，请重新提问。";

    private final RagChatSessionService sessionService;
    private final ObjectMapper objectMapper;
    private final TraceIdService traceIdService;

    public RagChatController(RagChatSessionService sessionService) {
        this(sessionService, new ObjectMapper().findAndRegisterModules(), new TraceIdService());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RagChatController(RagChatSessionService sessionService, ObjectMapper objectMapper,
                             TraceIdService traceIdService) {
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
        this.traceIdService = traceIdService;
    }

    /**
     * 创建新会话
     */
    @PostMapping("/sessions")
    public Result<SessionDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(sessionService.listSessions());
    }

    /**
     * 获取会话详情（包含消息历史）
     * GET /api/v1/chat/sessions/{sessionId}
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
        return Result.success(sessionService.getSessionDetail(sessionId));
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateTitleRequest request) {
        sessionService.updateSessionTitle(sessionId, request.title());
        return Result.success(null);
    }

    /**
     * 切换会话置顶状态
     * PUT /api/v1/chat/sessions/{sessionId}/pin
     */
    @PutMapping("/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        sessionService.togglePin(sessionId);
        return Result.success(null);
    }

    /**
     * 更新会话知识库
     */
    @PutMapping("/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateSessionKnowledgeBases(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateKnowledgeBasesRequest request) {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds());
        return Result.success(null);
    }

    /**
     * 删除会话
     * DELETE /api/v1/chat/sessions/{sessionId}
     */
    @DeleteMapping("/sessions/{sessionId}")
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
    @PostMapping(value = "/sessions/{sessionId}/messages/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request,
            @RequestHeader(value = "X-SSE-Protocol", required = false) String protocol) {

        return sendMessageStreamInternal(sessionId, request, "v1".equalsIgnoreCase(protocol));
    }

    /** 保留给不经过 Spring MVC 的单元测试和内部调用。 */
    Flux<ServerSentEvent<String>> sendMessageStream(
            Long sessionId, SendMessageRequest request) {
        return sendMessageStreamInternal(sessionId, request, false);
    }

    private Flux<ServerSentEvent<String>> sendMessageStreamInternal(
            Long sessionId, SendMessageRequest request, boolean structured) {

        log.info("收到 RAG 聊天流式请求: sessionId={}, question={}, 线程: {} (虚拟线程: {})",
            sessionId, request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());

        // 1. 准备消息（保存用户消息，创建 AI 消息占位）
        Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

        // 2. 获取流式响应
        StringBuffer fullContent = new StringBuffer();
        AtomicBoolean messagePersisted = new AtomicBoolean(false);
        String traceId = traceIdService.currentOrCreate();
        AtomicLong sequence = new AtomicLong(0);
        Flux<String> answerStream;
        try {
            answerStream = structured
                ? sessionService.getStreamAnswer(sessionId, request.question(), messageId, traceId)
                : sessionService.getStreamAnswer(sessionId, request.question(), messageId);
        } catch (Exception e) {
            // getStreamAnswer 在请求线程同步读取会话与历史；异常也转成流错误，统一完成占位消息。
            answerStream = Flux.error(e);
        }

        Flux<String> eventStream = structured
            ? Flux.concat(Flux.just("__sse_start__"), answerStream)
                .onErrorResume(error -> Flux.just("__sse_error__:" + safeErrorMessage(error)))
                .concatWithValues("__sse_done__")
            : answerStream;
        return eventStream
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
            .map(chunk -> toServerSentEvent(chunk, structured, traceId, sequence.incrementAndGet()))
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

    private ServerSentEvent<String> toServerSentEvent(String chunk, boolean structured,
                                                       String traceId, long sequence) {
        if (!structured) {
            return ServerSentEvent.<String>builder()
                .data(isPrefixedEvent(chunk) ? chunk : escapeChunk(chunk)).build();
        }
        try {
            SseEventEnvelope envelope = SseEventEnvelope.fromRaw(traceId, sequence, chunk, objectMapper);
            return ServerSentEvent.<String>builder()
                .id(traceId + ":" + sequence)
                .event(envelope.event())
                .data(objectMapper.writeValueAsString(envelope))
                .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder()
                .id(traceId + ":" + sequence)
                .event("error")
                .data("{\"traceId\":\"" + traceId + "\",\"sequence\":" + sequence
                    + ",\"stage\":\"protocol\",\"event\":\"error\",\"payload\":\"serialization failed\"}")
                .build();
        }
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
            || chunk.startsWith("intent:") || chunk.startsWith("route:")
            || chunk.startsWith("card:") || chunk.startsWith("card_choice:")
            || chunk.equals("__sse_start__") || chunk.equals("__sse_done__")
            || chunk.startsWith("__sse_error__:"));
    }

    private static String escapeChunk(String chunk) {
        return chunk.replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescapeChunk(String chunk) {
        return chunk.replace("\\n", "\n").replace("\\r", "\r");
    }

    private static String safeErrorMessage(Throwable error) {
        String message = error == null ? "stream failed" : error.getMessage();
        if (message == null || message.isBlank()) {
            return "stream failed";
        }
        String normalized = message.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }
}
