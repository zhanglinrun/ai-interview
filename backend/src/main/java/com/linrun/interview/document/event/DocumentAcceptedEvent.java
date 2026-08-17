package com.linrun.interview.document.event;

/**
 * 文档已落库（UPLOADED）事件。
 *
 * <p>批量上传先把原件写入 MinIO 并建档，再发此事件；
 * {@link DocumentAcceptedListener} 在请求线程外做 MinerU 解析和切块，
 * 避免 14 个大 PDF 在同一次 HTTP 请求里串行解析，离开页面或超时后只剩第一份。
 */
public record DocumentAcceptedEvent(Long docId, Long userId, boolean splitAfter) {
}
