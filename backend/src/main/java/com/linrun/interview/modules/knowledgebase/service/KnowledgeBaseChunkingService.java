package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.constant.SplitType;
import com.linrun.interview.modules.knowledgebase.model.DocumentSplitParam;
import com.linrun.interview.modules.knowledgebase.service.splitter.DocumentSplitterFactory;
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
    return new DocumentSplitParam(SplitType.BROTHER.name(), chunkSize, overlap, null, null, null);
  }

  /**
   * 空 body / 缺省 splitType 时回落默认 BROTHER；保留调用方传入的 chunkSize/overlap 等。
   */
  public DocumentSplitParam resolveSplitParam(DocumentSplitParam param) {
    DocumentSplitParam defaults = defaultSplitParam();
    if (param == null || param.splitType() == null || param.splitType().isBlank()) {
      return new DocumentSplitParam(
          defaults.splitType(),
          param != null && param.chunkSize() != null ? param.chunkSize() : defaults.chunkSize(),
          param != null && param.overlap() != null ? param.overlap() : defaults.overlap(),
          param != null ? param.titleLevel() : null,
          param != null ? param.separator() : null,
          param != null ? param.regex() : null);
    }
    return param;
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
