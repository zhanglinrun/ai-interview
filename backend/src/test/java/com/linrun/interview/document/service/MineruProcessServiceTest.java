package com.linrun.interview.document.service;

import com.linrun.interview.document.service.impl.ImageDescriptionService;
import com.linrun.interview.document.service.impl.DocumentParseTaskService;
import com.linrun.interview.document.service.impl.MineruMarkdownImageRewriter;
import com.linrun.interview.document.service.impl.MineruProcessServiceImpl;
import com.linrun.interview.document.service.impl.MineruZipExtractor;
import com.linrun.interview.document.config.MineruProperties;
import com.linrun.interview.document.constant.MineruFailureCode;
import com.linrun.interview.document.entity.DocumentParseTaskEntity;
import com.linrun.interview.document.constant.DocumentParseTaskStatus;
import com.linrun.interview.document.vo.MineruTaskResult;
import com.linrun.interview.document.vo.DocumentParseRequest;
import com.linrun.interview.document.constant.MineruTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MinerU 官方异步解析编排")
class MineruProcessServiceTest {

  @Test
  @DisplayName("提交预签名 URL、轮询并从 ZIP 提取 full.md 后上传 MinIO")
  void parsesThroughOfficialAsyncApi() throws Exception {
    Fixture fixture = fixture();
    when(fixture.storage.presignDownload(eq("knowledgebases/a.pdf"), any(Duration.class)))
        .thenReturn(URI.create("https://files.example.com/a.pdf?signature=secret"));
    when(fixture.client.submit(any(URI.class), eq("vlm"))).thenReturn("provider-1");
    when(fixture.client.getTask("provider-1")).thenReturn(new MineruTaskResult(
        MineruTaskStatus.SUCCEEDED, URI.create("https://result.example.com/r.zip"), null));
    when(fixture.client.downloadResult(any(URI.class))).thenReturn(zip("# 精准解析"));
    when(fixture.storage.uploadConvertedMarkdown(eq(11L), eq(13L), eq("# 精准解析")))
        .thenReturn("https://minio.example.com/converted/11/13/full.md");

    String result = fixture.service.processDocument(request());

    assertThat(result).isEqualTo("https://minio.example.com/converted/11/13/full.md");
    verify(fixture.client).submit(any(URI.class), eq("vlm"));
    verify(fixture.storage).uploadConvertedMarkdown(eq(11L), eq(13L), eq("# 精准解析"));
    verify(fixture.tika, never()).parseContent(any(byte[].class), any(String.class));
    verify(fixture.tasks).markSucceeded(fixture.task);
  }

  @Test
  @DisplayName("ZIP 内图片上传 MinIO 并重写 Markdown 引用")
  void uploadsImagesAndRewritesMarkdown() throws Exception {
    Fixture fixture = fixture();
    when(fixture.storage.presignDownload(eq("knowledgebases/a.pdf"), any(Duration.class)))
        .thenReturn(URI.create("https://files.example.com/a.pdf"));
    when(fixture.client.submit(any(URI.class), eq("vlm"))).thenReturn("provider-1");
    when(fixture.client.getTask("provider-1")).thenReturn(new MineruTaskResult(
        MineruTaskStatus.SUCCEEDED, URI.create("https://result.example.com/r.zip"), null));
    when(fixture.client.downloadResult(any(URI.class))).thenReturn(zipWithImage());
    when(fixture.storage.uploadConvertedImage(eq(11L), eq(13L), eq("fig1.png"), any(byte[].class)))
        .thenReturn("https://minio.example.com/converted/11/13/images/fig1.png");
    when(fixture.storage.uploadConvertedMarkdown(eq(11L), eq(13L), anyString()))
        .thenReturn("https://minio.example.com/converted/11/13/full.md");

    String result = fixture.service.processDocument(request());

    assertThat(result).isEqualTo("https://minio.example.com/converted/11/13/full.md");
    verify(fixture.storage).uploadConvertedImage(eq(11L), eq(13L), eq("fig1.png"), any(byte[].class));
    verify(fixture.storage).uploadConvertedMarkdown(eq(11L), eq(13L),
        org.mockito.ArgumentMatchers.contains("fig1.png"));
  }

  @Test
  @DisplayName("ZIP 内图片可被安全提取")
  void extractsImagesFromZip() throws Exception {
    MineruProperties properties = new MineruProperties();
    properties.setMaxCompressionRatio(10000);
    var pack = new MineruZipExtractor(properties).extractPackage(zipWithImage());
    assertThat(pack.markdown()).contains("fig1.png");
    assertThat(pack.images()).containsKey("fig1.png");
  }

  @Test
  @DisplayName("401 等官方失败显式记录原因并降级 Tika")
  void fallsBackToTikaWithVisibleReason() throws Exception {
    Fixture fixture = fixture();
    when(fixture.storage.presignDownload(eq("knowledgebases/a.pdf"), any(Duration.class)))
        .thenReturn(URI.create("https://files.example.com/a.pdf"));
    when(fixture.client.submit(any(URI.class), eq("vlm"))).thenThrow(
        new MineruClientException(MineruFailureCode.AUTHENTICATION, "认证失败"));
    when(fixture.tika.parseContent(any(byte[].class), eq("a.pdf"))).thenReturn("tika text");

    String result = fixture.service.processDocument(request());

    assertThat(result).isEqualTo("tika text");
    verify(fixture.tasks).markFailed(
        fixture.task, MineruFailureCode.AUTHENTICATION, "认证失败");
    verify(fixture.tasks).markFallbackSucceeded(
        fixture.task, MineruFailureCode.AUTHENTICATION);
  }

  private Fixture fixture() {
    MineruProperties properties = new MineruProperties();
    properties.setApiToken("token");
    properties.setAllowPrivateSourceUrls(true);
    properties.setPollIntervalMs(1);
    properties.setTaskTimeoutSeconds(2);
    properties.setMaxCompressionRatio(10000);
    DocumentParseService tika = mock(DocumentParseService.class);
    FileStorageService storage = mock(FileStorageService.class);
    MineruClient client = mock(MineruClient.class);
    DocumentParseTaskService tasks = mock(DocumentParseTaskService.class);
    KnowledgeDocumentVersionService versions = mock(KnowledgeDocumentVersionService.class);
    KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
    DocumentParseTaskEntity task = new DocumentParseTaskEntity();
    task.setId(1L);
    task.setStatus(DocumentParseTaskStatus.CREATED);
    task.setAttempt(0);
    when(tasks.create(any(DocumentParseRequest.class))).thenReturn(task);
    ImageDescriptionService imageDescriptions = mock(ImageDescriptionService.class);
    when(imageDescriptions.describe(anyString())).thenReturn("架构图说明");
    MineruMarkdownImageRewriter imageRewriter =
        new MineruMarkdownImageRewriter(imageDescriptions);
    MineruProcessServiceImpl service = new MineruProcessServiceImpl(
        properties,
        tika,
        storage,
        client,
        new MineruZipExtractor(properties),
        imageRewriter,
        tasks,
        versions,
        documents,
        null);
    return new Fixture(service, tika, storage, client, tasks, task);
  }

  private DocumentParseRequest request() {
    return new DocumentParseRequest(
        7L, 11L, 13L, "pdf".getBytes(StandardCharsets.UTF_8),
        "a.pdf", "application/pdf", "knowledgebases/a.pdf");
  }

  private byte[] zip(String markdown) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("full.md"));
      zip.write(markdown.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return output.toByteArray();
  }

  private byte[] zipWithImage() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("full.md"));
      zip.write("![架构图](images/fig1.png)".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("images/fig1.png"));
      zip.write(new byte[]{1, 2, 3, 4});
      zip.closeEntry();
    }
    return output.toByteArray();
  }

  private record Fixture(
      MineruProcessServiceImpl service,
      DocumentParseService tika,
      FileStorageService storage,
      MineruClient client,
      DocumentParseTaskService tasks,
      DocumentParseTaskEntity task
  ) {}
}
