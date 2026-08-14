package com.linrun.interview.document.service.impl;

import com.linrun.interview.document.vo.DocumentParseRequest;
import com.linrun.interview.document.vo.MineruExtractedPackage;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.document.service.DocumentParseService;
import com.linrun.interview.document.service.FileStorageService;
import com.linrun.interview.document.config.MineruProperties;
import com.linrun.interview.document.constant.FileType;
import com.linrun.interview.document.entity.DocumentParseTaskEntity;
import com.linrun.interview.document.entity.KnowledgeBaseVersionEntity;
import com.linrun.interview.document.service.impl.DocumentParseTaskService;
import com.linrun.interview.document.service.KnowledgeDocumentService;
import com.linrun.interview.document.service.KnowledgeDocumentVersionService;
import com.linrun.interview.document.constant.DocumentStatus;
import com.linrun.interview.document.service.MineruClient;
import com.linrun.interview.document.service.FileProcessService;
import com.linrun.interview.document.service.MineruClientException;
import com.linrun.interview.document.constant.MineruFailureCode;
import com.linrun.interview.document.vo.MineruTaskResult;
import com.linrun.interview.document.constant.MineruTaskStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 官方 MinerU 异步解析编排：私有 MinIO → 短时 URL → submit/poll → 安全 ZIP →
 * 图片上传 MinIO + Markdown 重写 + 视觉 alt → converted/{docId}/{versionId}/full.md。
 * 失败原因先持久化，再显式降级 Tika；外部调用均不在数据库事务中。
 */
@Slf4j
@Service
@EnableConfigurationProperties(MineruProperties.class)
public class MineruProcessServiceImpl implements FileProcessService {

  private final MineruProperties properties;
  private final DocumentParseService tikaParseService;
  private final FileStorageService storageService;
  private final MineruClient mineruClient;
  private final MineruZipExtractor zipExtractor;
  private final MineruMarkdownImageRewriter imageRewriter;
  private final DocumentParseTaskService taskService;
  private final KnowledgeDocumentVersionService versionService;
  private final KnowledgeDocumentService knowledgeDocumentService;
  private final MeterRegistry meterRegistry;

  public MineruProcessServiceImpl(
      MineruProperties properties,
      DocumentParseService tikaParseService,
      FileStorageService storageService,
      MineruClient mineruClient,
      MineruZipExtractor zipExtractor,
      MineruMarkdownImageRewriter imageRewriter,
      DocumentParseTaskService taskService,
      KnowledgeDocumentVersionService versionService,
      KnowledgeDocumentService knowledgeDocumentService,
      @Autowired(required = false) MeterRegistry meterRegistry
  ) {
    this.properties = properties;
    this.tikaParseService = tikaParseService;
    this.storageService = storageService;
    this.mineruClient = mineruClient;
    this.zipExtractor = zipExtractor;
    this.imageRewriter = imageRewriter;
    this.taskService = taskService;
    this.versionService = versionService;
    this.knowledgeDocumentService = knowledgeDocumentService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public boolean supports(FileType fileType) {
    return fileType == FileType.PDF || fileType == FileType.DOC || fileType == FileType.HTML;
  }

  @Override
  public String processDocument(byte[] fileBytes, String fileName) {
    return processDocument(new DocumentParseRequest(
        null, null, null, fileBytes, fileName, null, null));
  }

  @Override
  public String processDocument(DocumentParseRequest request) {
    DocumentParseTaskEntity task = request.persistentContextAvailable()
        ? taskService.create(request) : null;
    if (!properties.isEnabled()) {
      return fallback(request, task, MineruFailureCode.DISABLED);
    }
    if (properties.getApiToken() == null || properties.getApiToken().isBlank()) {
      return fallback(request, task, MineruFailureCode.CONFIGURATION);
    }
    if (request.storageKey() == null || request.storageKey().isBlank()) {
      return fallback(request, task, MineruFailureCode.PUBLIC_URL_UNAVAILABLE);
    }

    long started = System.nanoTime();
    try {
      URI sourceUrl = createPublicSourceUrl(request.storageKey());
      String providerTaskId = mineruClient.submit(sourceUrl, modelVersion(request));
      taskService.markSubmitted(task, providerTaskId, nextPollAt());
      String markdown = pollUntilCompleted(task, providerTaskId, request);
      taskService.markSucceeded(task);
      recordMetric("mineru", MineruFailureCode.UNKNOWN, started);
      log.info("MinerU 精准解析成功: docId={}, versionId={}, resultChars={}",
          request.documentId(), request.versionId(), markdown.length());
      return markdown;
    } catch (MineruClientException e) {
      taskService.markFailed(task, e.failureCode(), e.getMessage());
      log.warn("MinerU 精准解析失败并降级 Tika: docId={}, versionId={}, code={}",
          request.documentId(), request.versionId(), e.failureCode());
      log.debug("MinerU 精准解析失败堆栈: docId={}, versionId={}",
          request.documentId(), request.versionId(), e);
      recordMetric("fallback", e.failureCode(), started);
      return fallback(request, task, e.failureCode());
    } catch (Exception e) {
      taskService.markFailed(task, MineruFailureCode.UNKNOWN, "MinerU 编排失败");
      log.warn("MinerU 精准解析异常并降级 Tika: docId={}, versionId={}",
          request.documentId(), request.versionId(), e);
      recordMetric("fallback", MineruFailureCode.UNKNOWN, started);
      return fallback(request, task, MineruFailureCode.UNKNOWN);
    }
  }

  private String pollUntilCompleted(
      DocumentParseTaskEntity task,
      String providerTaskId,
      DocumentParseRequest request
  ) throws MineruClientException {
    Instant deadline = Instant.now().plusSeconds(Math.max(properties.getTaskTimeoutSeconds(), 1));
    while (Instant.now().isBefore(deadline)) {
      MineruTaskResult result = mineruClient.getTask(providerTaskId);
      switch (result.status()) {
        case SUCCEEDED -> {
          byte[] zip = mineruClient.downloadResult(result.resultZipUrl());
          return processZipResult(request, zip);
        }
        case FAILED -> throw new MineruClientException(
            MineruFailureCode.TASK_FAILED, "MinerU provider task 执行失败");
        case PENDING, RUNNING -> {
          taskService.markPolling(task, nextPollAt());
          pauseBeforeNextPoll();
        }
      }
    }
    throw new MineruClientException(MineruFailureCode.TASK_TIMEOUT, "MinerU provider task 超时");
  }

  private URI createPublicSourceUrl(String storageKey) throws MineruClientException {
    URI sourceUrl;
    try {
      sourceUrl = storageService.presignDownload(
          storageKey, Duration.ofSeconds(properties.getPresignedUrlTtlSeconds()));
    } catch (Exception e) {
      throw new MineruClientException(
          MineruFailureCode.PUBLIC_URL_UNAVAILABLE, "无法生成 MinerU 文件下载地址", e);
    }
    if (properties.isAllowPrivateSourceUrls()) {
      return sourceUrl;
    }
    try {
      if (sourceUrl.getHost() == null || !"https".equalsIgnoreCase(sourceUrl.getScheme())) {
        throw new MineruClientException(
            MineruFailureCode.PUBLIC_URL_UNAVAILABLE, "MinerU 文件地址必须是公网 HTTPS");
      }
      for (InetAddress address : InetAddress.getAllByName(sourceUrl.getHost())) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
          throw new MineruClientException(
              MineruFailureCode.PUBLIC_URL_UNAVAILABLE, "MinerU 文件地址不是公网地址");
        }
      }
      return sourceUrl;
    } catch (MineruClientException e) {
      throw e;
    } catch (Exception e) {
      throw new MineruClientException(
          MineruFailureCode.PUBLIC_URL_UNAVAILABLE, "无法校验 MinerU 文件下载地址", e);
    }
  }

  /**
   * 应用重启补偿：只轮询一次，未完成则更新 nextPollAt；成功或失败降级后补齐版本正文与状态。
   */
  public boolean recoverStaleTask(DocumentParseTaskEntity task) {
    if (task == null || task.getStatus() == null || task.getStatus().isTerminal()
        || task.getProviderTaskId() == null || task.getProviderTaskId().isBlank()) {
      return false;
    }
    try {
      MineruTaskResult result = mineruClient.getTask(task.getProviderTaskId());
      if (result.status() == MineruTaskStatus.PENDING
          || result.status() == MineruTaskStatus.RUNNING) {
        if (isProviderTaskExpired(task)) {
          return recoverWithTika(task, MineruFailureCode.TASK_TIMEOUT);
        }
        taskService.markPolling(task, nextPollAt());
        return false;
      }
      if (result.status() == MineruTaskStatus.FAILED) {
        return recoverWithTika(task, MineruFailureCode.TASK_FAILED);
      }
      String converted = processZipResult(buildRequestFromTask(task),
          mineruClient.downloadResult(result.resultZipUrl()));
      applyRecoveredMarkdown(task, converted);
      taskService.markSucceeded(task);
      return true;
    } catch (MineruClientException e) {
      taskService.markFailed(task, e.failureCode(), e.getMessage());
      return recoverWithTika(task, e.failureCode());
    } catch (Exception e) {
      taskService.markFailed(task, MineruFailureCode.UNKNOWN, "MinerU 补偿失败");
      return recoverWithTika(task, MineruFailureCode.UNKNOWN);
    }
  }

  private boolean recoverWithTika(
      DocumentParseTaskEntity task,
      MineruFailureCode reason
  ) {
    try {
      byte[] bytes = storageService.downloadFile(task.getStorageKey());
      DocumentParseRequest request = new DocumentParseRequest(
          task.getUserId(),
          task.getDocumentId(),
          task.getVersionId(),
          bytes,
          task.getFileName(),
          task.getContentType(),
          task.getStorageKey());
      String markdown = fallback(request, task, reason);
      applyRecoveredMarkdown(task, markdown);
      return true;
    } catch (Exception e) {
      taskService.markFallbackFailed(task, MineruFailureCode.FALLBACK_FAILED, "补偿降级失败");
      log.warn("MinerU 补偿降级失败: parseTaskId={}, docId={}, versionId={}",
          task.getId(), task.getDocumentId(), task.getVersionId(), e);
      return false;
    }
  }

  private void applyRecoveredMarkdown(DocumentParseTaskEntity task, String converted) {
    KnowledgeBaseVersionEntity version = versionService.findById(task.getVersionId())
        .filter(item -> task.getDocumentId().equals(item.getDocId()))
        .orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, "解析任务关联的文档版本不存在"));
    if (converted != null && (converted.startsWith("http://") || converted.startsWith("https://"))) {
      version.setConvertedDocUrl(converted);
      version.setConvertedContent(null);
    } else {
      version.setConvertedContent(converted);
    }
    versionService.updateVersion(version);
    knowledgeDocumentService.advanceDocumentAndVersionStatus(
        task.getDocumentId(), task.getVersionId(), DocumentStatus.CONVERTED);
  }

  /**
   * MinerU ZIP 产物处理：提取 full.md 与图片 → 图片上传 MinIO → 重写引用 + 视觉 alt → 上传 full.md。
   * 无持久化上下文时仅返回 Markdown 文本（单测/无 docId 场景）。
   */
  private String processZipResult(DocumentParseRequest request, byte[] zipBytes)
      throws MineruClientException {
    if (!request.persistentContextAvailable()) {
      return zipExtractor.extractFullMarkdown(zipBytes);
    }
    MineruExtractedPackage pack = zipExtractor.extractPackage(zipBytes);
    Map<String, String> imageUrlByFileName = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> entry : pack.images().entrySet()) {
      String imageUrl = storageService.uploadConvertedImage(
          request.documentId(), request.versionId(), entry.getKey(), entry.getValue());
      imageUrlByFileName.put(entry.getKey(), imageUrl);
    }
    String rewritten = imageRewriter.rewrite(pack.markdown(), imageUrlByFileName);
    return storageService.uploadConvertedMarkdown(
        request.documentId(), request.versionId(), rewritten);
  }

  private DocumentParseRequest buildRequestFromTask(DocumentParseTaskEntity task) {
    return new DocumentParseRequest(
        task.getUserId(),
        task.getDocumentId(),
        task.getVersionId(),
        new byte[] {0},
        task.getFileName(),
        task.getContentType(),
        task.getStorageKey());
  }

  private boolean isProviderTaskExpired(DocumentParseTaskEntity task) {
    LocalDateTime startedAt = task.getStartedAt() != null
        ? task.getStartedAt() : task.getCreatedAt();
    return startedAt != null && startedAt.plusSeconds(
        Math.max(properties.getTaskTimeoutSeconds(), 1)).isBefore(LocalDateTime.now());
  }

  private String fallback(
      DocumentParseRequest request,
      DocumentParseTaskEntity task,
      MineruFailureCode reason
  ) {
    try {
      String content = tikaParseService.parseContent(request.fileBytes(), request.fileName());
      if (content == null || content.isBlank()) {
        throw new BusinessException(
            ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "Tika 降级解析结果为空");
      }
      taskService.markFallbackSucceeded(task, reason);
      log.info("文档已使用 Tika 降级解析: docId={}, versionId={}, reason={}",
          request.documentId(), request.versionId(), reason);
      return content;
    } catch (BusinessException e) {
      taskService.markFallbackFailed(task, MineruFailureCode.FALLBACK_FAILED, "Tika 降级失败");
      throw e;
    } catch (Exception e) {
      taskService.markFallbackFailed(task, MineruFailureCode.FALLBACK_FAILED, "Tika 降级失败");
      throw new BusinessException(
          ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文件精准解析和降级解析均失败", e);
    }
  }

  private String modelVersion(DocumentParseRequest request) {
    String fileName = request.fileName().toLowerCase(Locale.ROOT);
    String contentType = request.contentType() == null
        ? "" : request.contentType().toLowerCase(Locale.ROOT);
    return fileName.endsWith(".html") || fileName.endsWith(".htm")
        || contentType.contains("html")
        ? properties.getHtmlModelVersion() : properties.getModelVersion();
  }

  private LocalDateTime nextPollAt() {
    return LocalDateTime.now().plusNanos(
        TimeUnit.MILLISECONDS.toNanos(Math.max(properties.getPollIntervalMs(), 1)));
  }

  private void pauseBeforeNextPoll() throws MineruClientException {
    try {
      Thread.sleep(Math.max(properties.getPollIntervalMs(), 1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MineruClientException(
          MineruFailureCode.INTERRUPTED, "MinerU 轮询被中断", e);
    }
  }

  private void recordMetric(String result, MineruFailureCode code, long started) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.timer("app.ai.document.parse.latency", "provider", "mineru", "result", result)
        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    meterRegistry.counter(
        "app.ai.document.parse.requests",
        "provider", "mineru",
        "result", result,
        "failure_code", code.name()).increment();
  }

  public String processDocument(MultipartFile file) {
    try {
      return processDocument(file.getBytes(), file.getOriginalFilename());
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(
          ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文件读取失败", e);
    }
  }

  public boolean isMineruEnabled() {
    return properties.isEnabled();
  }
}
