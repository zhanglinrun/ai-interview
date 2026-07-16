package com.linrun.interview.modules.knowledgebase.service.parse;

import com.linrun.interview.infrastructure.file.DocumentParseService;
import com.linrun.interview.infrastructure.file.FileStorageService;
import com.linrun.interview.modules.knowledgebase.config.MineruProperties;
import com.linrun.interview.modules.knowledgebase.model.DocumentParseTaskEntity;
import com.linrun.interview.modules.knowledgebase.model.DocumentParseTaskStatus;
import com.linrun.interview.modules.knowledgebase.service.DocumentParseTaskService;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeDocumentService;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import com.linrun.interview.modules.knowledgebase.service.parse.mineru.MineruClient;
import com.linrun.interview.modules.knowledgebase.service.parse.mineru.MineruClientException;
import com.linrun.interview.modules.knowledgebase.service.parse.mineru.MineruFailureCode;
import com.linrun.interview.modules.knowledgebase.service.parse.mineru.MineruTaskResult;
import com.linrun.interview.modules.knowledgebase.service.parse.mineru.MineruTaskStatus;
import com.linrun.interview.modules.knowledgebase.service.parse.mineru.MineruZipExtractor;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MinerU 官方异步解析编排")
class MineruProcessServiceTest {

  @Test
  @DisplayName("提交预签名 URL、轮询并从 ZIP 提取 full.md")
  void parsesThroughOfficialAsyncApi() throws Exception {
    Fixture fixture = fixture();
    when(fixture.storage.presignDownload(eq("knowledgebases/a.pdf"), any(Duration.class)))
        .thenReturn(URI.create("https://files.example.com/a.pdf?signature=secret"));
    when(fixture.client.submit(any(URI.class), eq("vlm"))).thenReturn("provider-1");
    when(fixture.client.getTask("provider-1")).thenReturn(new MineruTaskResult(
        MineruTaskStatus.SUCCEEDED, URI.create("https://result.example.com/r.zip"), null));
    when(fixture.client.downloadResult(any(URI.class))).thenReturn(zip("# 精准解析"));

    String result = fixture.service.processDocument(request());

    assertThat(result).isEqualTo("# 精准解析");
    verify(fixture.client).submit(any(URI.class), eq("vlm"));
    verify(fixture.tika, never()).parseContent(any(byte[].class), any(String.class));
    verify(fixture.tasks).markSucceeded(fixture.task);
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
    MineruProcessService service = new MineruProcessService(
        properties,
        tika,
        storage,
        client,
        new MineruZipExtractor(properties),
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

  private record Fixture(
      MineruProcessService service,
      DocumentParseService tika,
      FileStorageService storage,
      MineruClient client,
      DocumentParseTaskService tasks,
      DocumentParseTaskEntity task
  ) {}
}
