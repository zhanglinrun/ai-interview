package com.linrun.interview.modules.knowledgebase.service.parse.mineru;

import com.linrun.interview.modules.knowledgebase.config.MineruProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MinerU ZIP 安全提取")
class MineruZipExtractorTest {

  @Test
  @DisplayName("只提取 full.md")
  void extractsFullMarkdown() throws Exception {
    MineruProperties properties = new MineruProperties();
    properties.setMaxCompressionRatio(10000);
    String markdown = new MineruZipExtractor(properties).extractFullMarkdown(zip(
        new Entry("images/a.png", new byte[]{1, 2}),
        new Entry("nested/full.md", "# 面试证据".getBytes(StandardCharsets.UTF_8))));

    assertThat(markdown).isEqualTo("# 面试证据");
  }

  @Test
  @DisplayName("拒绝路径穿越")
  void rejectsZipSlip() throws Exception {
    MineruProperties properties = new MineruProperties();
    assertThatThrownBy(() -> new MineruZipExtractor(properties).extractFullMarkdown(zip(
        new Entry("../full.md", "bad".getBytes(StandardCharsets.UTF_8)))))
        .isInstanceOf(MineruClientException.class)
        .extracting(error -> ((MineruClientException) error).failureCode())
        .isEqualTo(MineruFailureCode.ZIP_SECURITY_VIOLATION);
  }

  @Test
  @DisplayName("缺少 full.md 不伪装为精准解析成功")
  void requiresFullMarkdown() throws Exception {
    MineruProperties properties = new MineruProperties();
    assertThatThrownBy(() -> new MineruZipExtractor(properties).extractFullMarkdown(zip(
        new Entry("result.txt", "text".getBytes(StandardCharsets.UTF_8)))))
        .isInstanceOf(MineruClientException.class)
        .extracting(error -> ((MineruClientException) error).failureCode())
        .isEqualTo(MineruFailureCode.FULL_MD_MISSING);
  }

  private byte[] zip(Entry... entries) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (Entry entry : entries) {
        zip.putNextEntry(new ZipEntry(entry.name()));
        zip.write(entry.bytes());
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }

  private record Entry(String name, byte[] bytes) {}
}
