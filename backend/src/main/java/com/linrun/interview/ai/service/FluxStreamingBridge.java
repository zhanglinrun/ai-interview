package com.linrun.interview.ai.service;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * LangChain4j 流式回调 → Reactor Flux 桥接。
 *
 * <p>LC4j 的 {@link StreamingChatModel#chat(ChatRequest, StreamingChatResponseHandler)} 是回调式 API
 * （onPartialResponse/onCompleteResponse/onError），而前端 SSE 需要的是 {@code Flux<String>}。
 * 本工具用 {@link Flux#create} 把回调包成 Flux：
 * <ul>
 *   <li>{@code onPartialResponse(token)} → {@code sink.next(token)}</li>
 *   <li>{@code onCompleteResponse} → {@code sink.complete()}</li>
 *   <li>{@code onError(e)} → {@code sink.error(e)}（交给上层 onErrorResume 兜底）</li>
 * </ul>
 *
 * <p>底层 chat 调用可能在同一线程阻塞直到流结束，因此 {@code subscribeOn(boundedElastic)} 让阻塞调用
 * 跑在弹性线程池，不占虚拟线程/事件循环。取消订阅时通过 sink onCancel 标记，handler 后续回调被忽略。
 */
@Slf4j
public final class FluxStreamingBridge {

    private FluxStreamingBridge() {
    }

    /**
     * 把一次流式 chat 调用桥接成 {@code Flux<String>}，每个 token 作为一个元素。
     *
     * @param streamingChatModel 流式模型（通常经 SafeGuardStreamingChatModel 包装）
     * @param chatRequest        请求
     * @return token Flux；错误经 onError 透传给上层
     */
    public static Flux<String> stream(StreamingChatModel streamingChatModel, ChatRequest chatRequest) {
        return Flux.<String>create(sink -> {
            StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (sink.isCancelled()) {
                        return;
                    }
                    if (partialResponse != null && !partialResponse.isEmpty()) {
                        sink.next(partialResponse);
                    }
                }

                @Override
                public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                    if (sink.isCancelled()) {
                        return;
                    }
                    sink.complete();
                }

                @Override
                public void onError(Throwable error) {
                    if (sink.isCancelled()) {
                        return;
                    }
                    sink.error(error);
                }
            };
            try {
                streamingChatModel.chat(chatRequest, handler);
            } catch (Throwable e) {
                if (!sink.isCancelled()) {
                    sink.error(e);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
