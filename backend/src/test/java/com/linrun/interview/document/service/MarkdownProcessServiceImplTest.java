package com.linrun.interview.document.service;

import com.linrun.interview.document.service.impl.MarkdownProcessServiceImpl;
import com.linrun.interview.document.service.impl.ImageDescriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Markdown 解析服务")
class MarkdownProcessServiceImplTest {

  @Mock
  private ImageDescriptionService imageDescriptionService;

  @InjectMocks
  private MarkdownProcessServiceImpl service;

  @Test
  @DisplayName("公网图片 URL 应替换 alt 文本")
  void enrichesPublicImageAlts() {
    when(imageDescriptionService.describe("https://cdn.example.com/a.png"))
        .thenReturn("系统架构图");
    String md = "# 标题\n![placeholder](https://cdn.example.com/a.png)\n正文";
    String out = service.processDocument(md.getBytes(StandardCharsets.UTF_8), "note.md");
    assertThat(out).contains("![系统架构图](https://cdn.example.com/a.png)");
  }

  @Test
  @DisplayName("相对路径图片不调用视觉模型")
  void skipsRelativeImagePaths() {
    String md = "![](images/local.png)";
    assertThat(service.processDocument(md.getBytes(StandardCharsets.UTF_8), "note.md"))
        .isEqualTo(md);
  }
}
