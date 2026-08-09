package com.linrun.interview.document.service;
import com.linrun.interview.document.service.ExcelSplitter;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;

import com.linrun.interview.document.constant.SplitType;
import com.linrun.interview.document.vo.DocumentSplitParam;
import com.linrun.interview.document.service.DocumentSplitterFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库分片服务：支持工厂策略 + ExcelSplitter 入口。
 */
@Slf4j
@Service
public class KnowledgeBaseChunkingService {

  private static final int MIN_CHUNK_CHARS = 5;

  private final KnowledgeBaseQueryProperties queryProperties;

  public KnowledgeBaseChunkingService(KnowledgeBaseQueryProperties queryProperties) {
    this.queryProperties = queryProperties == null ? new KnowledgeBaseQueryProperties() : queryProperties;
  }

  public DocumentSplitParam defaultSplitParam() {
    int chunkSize = Math.max(64, queryProperties.getChunkSizeChars());
    int overlap = Math.max(0, queryProperties.getChunkOverlapChars());
    return new DocumentSplitParam(SplitType.PARENT_CHILD.name(), chunkSize, overlap, null, null, null);
  }

  /**
   * 空 body / 缺省 splitType 时回落默认 PARENT_CHILD；统一保证 overlap 小于 chunkSize，
   * 避免滑动窗口起点不前进导致死循环。
   */
  public DocumentSplitParam resolveSplitParam(DocumentSplitParam param) {
    DocumentSplitParam defaults = defaultSplitParam();
    String splitType = param == null || param.splitType() == null || param.splitType().isBlank()
        ? defaults.splitType()
        : param.splitType();
    int chunkSize = param != null && param.chunkSize() != null && param.chunkSize() > 0
        ? param.chunkSize()
        : defaults.chunkSize();
    int requestedOverlap = param != null && param.overlap() != null
        ? Math.max(0, param.overlap())
        : defaults.overlap();
    int overlap = Math.min(requestedOverlap, Math.max(0, chunkSize - 1));
    return new DocumentSplitParam(
        splitType,
        chunkSize,
        overlap,
        param != null ? param.titleLevel() : null,
        param != null ? param.separator() : null,
        param != null ? param.regex() : null);
  }

  public List<TextSegment> split(String content) {
    return split(content, defaultSplitParam());
  }

  public List<TextSegment> split(String content, DocumentSplitParam param) {
    if (content == null || content.isBlank()) {
      return List.of();
    }
    DocumentSplitParam effective = resolveSplitParam(param);
    DocumentSplitter splitter = DocumentSplitterFactory.getInstance(effective);
    List<TextSegment> rawChunks = splitter.split(Document.from(content));
    List<TextSegment> effectiveChunks = new ArrayList<>(rawChunks.size());
    int filtered = 0;
    for (TextSegment chunk : rawChunks) {
      String text = chunk.text() == null ? "" : chunk.text().trim();
      if (text.length() < MIN_CHUNK_CHARS) {
        filtered++;
        continue;
      }
      effectiveChunks.add(chunk);
    }
    log.info("分片完成: strategy={}, chunkSize={}, overlap={}, raw={}, filtered={}, effective={}",
        effective.splitType(),
        effective.chunkSize(),
        effective.overlap(),
        rawChunks.size(), filtered, effectiveChunks.size());
    return effectiveChunks;
  }
}
