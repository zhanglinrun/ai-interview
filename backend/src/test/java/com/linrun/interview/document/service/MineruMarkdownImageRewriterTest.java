package com.linrun.interview.document.service;

import com.linrun.interview.document.service.impl.ImageDescriptionService;
import com.linrun.interview.document.service.impl.MineruMarkdownImageRewriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinerU Markdown 图片改写")
class MineruMarkdownImageRewriterTest {

  @Mock
  private ImageDescriptionService imageDescriptionService;

  @InjectMocks
  private MineruMarkdownImageRewriter rewriter;

  @Test
  @DisplayName("相对路径应替换为 MinIO URL")
  void rewritesRelativeImagePaths() {
    when(imageDescriptionService.describe(
        "http://localhost/bucket/converted/1/2/images/fig1.png")).thenReturn(null);
    String md = "见图：![架构](images/fig1.png)\n正文";
    String out = rewriter.rewrite(md, Map.of(
        "fig1.png", "http://localhost/bucket/converted/1/2/images/fig1.png"));
    assertThat(out).contains("![架构](http://localhost/bucket/converted/1/2/images/fig1.png)");
  }

  @Test
  @DisplayName("找不到映射时保留原引用")
  void keepsOriginalWhenMissing() {
    String md = "![](images/missing.png)";
    assertThat(rewriter.rewrite(md, Map.of("other.png", "http://x"))).isEqualTo(md);
  }
}

