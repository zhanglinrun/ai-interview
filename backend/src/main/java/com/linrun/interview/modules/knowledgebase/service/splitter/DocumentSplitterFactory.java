package com.linrun.interview.modules.knowledgebase.service.splitter;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.knowledgebase.constant.SplitType;
import com.linrun.interview.modules.knowledgebase.model.DocumentSplitParam;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.document.splitter.DocumentByWordSplitter;

/**
 * 文档切块工厂（对齐 know-engine {@code DocumentSplitterFactory}，默认 BROTHER）。
 */
public final class DocumentSplitterFactory {

  private DocumentSplitterFactory() {
  }

  public static DocumentSplitter getInstance(DocumentSplitParam param) {
    if (param == null || param.splitType() == null || param.splitType().isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "切块策略不能为空");
    }
    SplitType splitType;
    try {
      splitType = SplitType.valueOf(param.splitType().trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的切块策略: " + param.splitType());
    }
    int chunkSize = param.chunkSize() != null && param.chunkSize() > 0 ? param.chunkSize() : 800;
    int overlap = param.overlap() != null && param.overlap() >= 0 ? param.overlap() : 80;
    return switch (splitType) {
      case BROTHER -> new MarkdownHeaderBrotherTextSplitter(chunkSize, overlap);
      case TITLE -> {
        int titleLevel = param.titleLevel() != null && param.titleLevel() > 0 ? param.titleLevel() : 2;
        yield new MarkdownHeaderParentTextSplitter(titleLevel, false, false, chunkSize, overlap);
      }
      case SMART -> new MarkdownHeaderParentTextSplitter(chunkSize, Math.max(0, (int) (chunkSize * 0.1)));
      case LENGTH -> new DocumentByWordSplitter(chunkSize, overlap);
      case SEPARATOR -> {
        String separator = param.separator() != null && !param.separator().isBlank() ? param.separator() : "\n\n";
        yield new DocumentByRegexSplitter(separator, "\n\n", chunkSize, overlap);
      }
      case REGEX -> {
        String regex = param.regex();
        if (regex == null || regex.isBlank()) {
          throw new BusinessException(ErrorCode.BAD_REQUEST, "REGEX 策略需要提供 regex 参数");
        }
        yield new DocumentByRegexSplitter(regex, "\n\n", chunkSize, overlap);
      }
    };
  }
}
