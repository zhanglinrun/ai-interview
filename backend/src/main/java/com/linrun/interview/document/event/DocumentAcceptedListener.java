package com.linrun.interview.document.event;

import com.linrun.interview.document.service.DocumentProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 接收 {@link DocumentAcceptedEvent}，在独立线程池里解析并按需切块。
 *
 * <p>不用 {@code AFTER_COMMIT}：accept 落库不包事务，事件在写入完成后同步发布即可。
 * 失败只打日志，由补偿任务继续收敛 UPLOADED/CONVERTING。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentAcceptedListener {

    private final DocumentProcessService documentProcessService;

    @Async("documentConvertExecutor")
    @EventListener
    public void onDocumentAccepted(DocumentAcceptedEvent event) {
        if (event == null || event.docId() == null) {
            return;
        }
        log.info("收到文档入库事件，开始异步解析: docId={}, userId={}, splitAfter={}",
            event.docId(), event.userId(), event.splitAfter());
        try {
            documentProcessService.processAcceptedDocument(event.docId(), event.splitAfter());
            log.info("异步解析完成: docId={}", event.docId());
        } catch (Exception e) {
            log.error("异步解析失败，留待补偿: docId={}, error={}",
                event.docId(), e.getMessage(), e);
        }
    }
}
