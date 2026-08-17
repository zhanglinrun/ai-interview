package com.linrun.interview.document.event;

import com.linrun.interview.document.service.DocumentProcessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("文档入库事件监听")
class DocumentAcceptedListenerTest {

  @Mock private DocumentProcessService documentProcessService;
  @InjectMocks private DocumentAcceptedListener listener;

  @Test
  @DisplayName("按事件触发异步解析")
  void processesAcceptedDocument() {
    listener.onDocumentAccepted(new DocumentAcceptedEvent(9L, 7L, true));
    verify(documentProcessService).processAcceptedDocument(9L, true);
  }

  @Test
  @DisplayName("空事件不调用解析")
  void ignoresNullEvent() {
    listener.onDocumentAccepted(null);
    verify(documentProcessService, never()).processAcceptedDocument(anyLong(), anyBoolean());
  }

  @Test
  @DisplayName("解析失败不向外抛，留给补偿任务")
  void swallowsProcessFailure() {
    doThrow(new RuntimeException("mineru down"))
        .when(documentProcessService).processAcceptedDocument(9L, true);

    listener.onDocumentAccepted(new DocumentAcceptedEvent(9L, 7L, true));

    verify(documentProcessService).processAcceptedDocument(9L, true);
  }
}
