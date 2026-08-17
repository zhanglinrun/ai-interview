package com.linrun.interview.document.service.impl;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;

import com.linrun.interview.document.constant.SplitType;
import com.linrun.interview.document.vo.DocumentSplitParam;
import com.linrun.interview.document.service.DocumentSplitterFactory;
import com.linrun.interview.rag.constant.MetadataKeyConstant;
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
    return new DocumentSplitParam(
        SplitType.TITLE.name(), chunkSize, overlap, DocumentSplitterFactory.DEFAULT_TITLE_LEVEL, null, null);
  }

  /**
   * 空 body / 缺省 splitType 时回落默认 TITLE（与 know-engine 按标题切分对齐）；
   * PARENT_CHILD 仍作为 TITLE 别名。统一保证 overlap 小于 chunkSize，避免滑窗死循环。
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
    int titleLevel = param != null && param.titleLevel() != null && param.titleLevel() > 0
        ? Math.min(6, param.titleLevel())
        : defaults.titleLevel();
    return new DocumentSplitParam(
        splitType,
        chunkSize,
        overlap,
        titleLevel,
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
      Integer skip = chunk.metadata() == null
          ? null
          : chunk.metadata().getInteger(MetadataKeyConstant.SKIP_EMBEDDING);
      boolean parentContext = skip != null && skip == 1;
      if (!parentContext && (text.length() < MIN_CHUNK_CHARS || ParentChildHierarchyLinker.isHeadingOnly(text))) {
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
