package com.linrun.interview.document.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.document.constant.FileType;
import com.linrun.interview.document.constant.KnowledgeBaseType;
import com.linrun.interview.document.service.impl.MarkdownProcessServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("文件解析器工厂路由")
class FileProcessServiceFactoryTest {

  @Mock private DocumentParseService documentParseService;

  private FileProcessServiceFactory factory;

  @BeforeEach
  void setUp() {
    FileProcessService dataQueryExcelProcessor = new FileProcessService() {
      @Override
      public boolean supports(FileType fileType) {
        return supports(fileType, KnowledgeBaseType.DATA_QUERY);
      }

      @Override
      public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        return (fileType == FileType.EXCEL || fileType == FileType.CSV)
            && knowledgeBaseType == KnowledgeBaseType.DATA_QUERY;
      }

      @Override
      public String processDocument(byte[] fileBytes, String fileName) {
        return null;
      }
    };
    factory = new FileProcessServiceFactory(List.of(
        new MarkdownProcessServiceImpl(
            mock(com.linrun.interview.document.service.impl.ImageDescriptionService.class)),
        dataQueryExcelProcessor));
  }

  @Test
  @DisplayName("DOCUMENT_SEARCH + MD 走 Markdown 解析器")
  void routesMarkdownForDocumentSearch() {
    FileProcessService processor = factory.get(FileType.MARKDOWN, KnowledgeBaseType.DOCUMENT_SEARCH);
    assertThat(processor).isInstanceOf(MarkdownProcessServiceImpl.class);
  }

  @Test
  @DisplayName("DOCUMENT_SEARCH + Excel 无工厂解析器（convert 阶段 passthrough 原文件）")
  void noProcessorForDocumentSearchExcel() {
    assertThatThrownBy(() -> factory.get(FileType.EXCEL, KnowledgeBaseType.DOCUMENT_SEARCH))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不支持的文件类型");
  }

  @Test
  @DisplayName("DATA_QUERY + Excel 走 DATA_QUERY 专用解析器")
  void routesExcelForDataQuery() {
    FileProcessService processor = factory.get(FileType.EXCEL, KnowledgeBaseType.DATA_QUERY);
    assertThat(processor.supports(FileType.EXCEL, KnowledgeBaseType.DATA_QUERY)).isTrue();
    assertThat(processor.supports(FileType.EXCEL, KnowledgeBaseType.DOCUMENT_SEARCH)).isFalse();
  }

  @Test
  @DisplayName("DATA_QUERY + PDF 无解析器应 fail-fast")
  void rejectsPdfForDataQuery() {
    assertThatThrownBy(() -> factory.get(FileType.PDF, KnowledgeBaseType.DATA_QUERY))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不支持的文件类型");
  }
}
