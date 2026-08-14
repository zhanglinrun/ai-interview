package com.linrun.interview.document.service;

/**
 * 文本清理服务：RAG/解析前的语义去噪与格式规范化。
 */
public interface TextCleaningService {

    String cleanText(String text);

    String cleanTextWithLimit(String text, int maxLength);

    String cleanToSingleLine(String text);

    String stripHtml(String text);
}
