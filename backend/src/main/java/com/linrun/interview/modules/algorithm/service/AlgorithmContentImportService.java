package com.linrun.interview.modules.algorithm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.modules.algorithm.mapper.AlgorithmContentImportMapper;
import com.linrun.interview.modules.algorithm.mapper.CodingProblemMapper;
import com.linrun.interview.modules.algorithm.mapper.CodingProblemVersionMapper;
import com.linrun.interview.modules.algorithm.model.AlgorithmCatalogContent;
import com.linrun.interview.modules.algorithm.model.AlgorithmContentImportEntity;
import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import com.linrun.interview.modules.algorithm.model.CodingProblemEntity;
import com.linrun.interview.modules.algorithm.model.CodingProblemVersionEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 仓库内 Hot 100 内容的校验与幂等导入。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlgorithmContentImportService {

  static final String CATALOG_RESOURCE = "algorithm-content/hot100-v1.json";

  private final ObjectMapper objectMapper;
  private final FileHashService fileHashService;
  private final AlgorithmContentValidator validator;
  private final AlgorithmContentImportMapper importMapper;
  private final CodingProblemMapper problemMapper;
  private final CodingProblemVersionMapper versionMapper;

  public AlgorithmContentValidator.ValidationReport validateClasspathCatalog() {
    return validator.validate(load().content());
  }

  @Transactional(rollbackFor = Exception.class)
  public ImportReport importClasspathCatalog() {
    LoadedCatalog loaded = load();
    var report = validator.validate(loaded.content());
    if (!report.valid()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "算法题库校验失败: " + String.join("；", report.errors()));
    }
    AlgorithmContentImportEntity existing = importMapper.selectOne(
        Wrappers.<AlgorithmContentImportEntity>lambdaQuery()
            .eq(AlgorithmContentImportEntity::getContentVersion, loaded.content().contentVersion()));
    if (existing != null) {
      if (existing.getChecksum().equalsIgnoreCase(report.calculatedChecksum())) {
        return new ImportReport(loaded.content().contentVersion(), true,
            report.problemCount(), report.enabledCount());
      }
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "算法 contentVersion 已存在但内容校验和不同，禁止覆盖已发布版本");
    }

    LocalDateTime now = LocalDateTime.now();
    Map<String, AlgorithmCatalogContent.ProblemVersionDefinition> enabledVersions =
        loaded.content().enabledProblems().stream()
            .collect(Collectors.toMap(
                AlgorithmCatalogContent.EnabledProblemDefinition::platformProblemId,
                AlgorithmCatalogContent.EnabledProblemDefinition::version));
    for (var definition : loaded.content().problems()) {
      CodingProblemEntity problem = CodingProblemEntity.builder()
          .catalogVersion(loaded.content().contentVersion())
          .hotRank(definition.hotRank())
          .platform(definition.platform())
          .platformProblemId(definition.platformProblemId())
          .slug(definition.slug())
          .title(definition.title())
          .difficulty(definition.difficulty())
          .tagsJson(writeJson(definition.tags()))
          .sourceUrl(definition.sourceUrl())
          .active(true)
          .createdAt(now)
          .updatedAt(now)
          .build();
      problemMapper.insert(problem);
      var version = enabledVersions.get(definition.platformProblemId());
      if (version != null && Boolean.TRUE.equals(version.enabled())) {
        insertVersion(problem.getId(), version, now);
      }
    }
    importMapper.insert(AlgorithmContentImportEntity.builder()
        .schemaVersion(loaded.content().schemaVersion())
        .contentVersion(loaded.content().contentVersion())
        .checksum(report.calculatedChecksum())
        .problemCount(report.problemCount())
        .enabledCount(report.enabledCount())
        .importedAt(now)
        .build());
    log.info("算法题库导入完成: version={}, problems={}, enabled={}",
        loaded.content().contentVersion(), report.problemCount(), report.enabledCount());
    return new ImportReport(loaded.content().contentVersion(), false,
        report.problemCount(), report.enabledCount());
  }

  LoadedCatalog load() {
    ClassPathResource resource = new ClassPathResource(CATALOG_RESOURCE);
    try (var input = resource.getInputStream()) {
      byte[] bytes = input.readAllBytes();
      AlgorithmCatalogContent content = objectMapper.readValue(bytes, AlgorithmCatalogContent.class);
      return new LoadedCatalog(content);
    } catch (IOException e) {
      throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "读取算法题库失败: " + CATALOG_RESOURCE, e);
    }
  }

  private void insertVersion(
      Long problemId,
      AlgorithmCatalogContent.ProblemVersionDefinition version,
      LocalDateTime now
  ) {
    boolean javaEnabled = enabled(version, CodingLanguage.JAVA21);
    boolean pythonEnabled = enabled(version, CodingLanguage.PYTHON3);
    versionMapper.insert(CodingProblemVersionEntity.builder()
        .problemId(problemId)
        .version(version.version())
        .statementText(version.statement())
        .constraintsJson(writeJson(version.constraints()))
        .publicExamplesJson(writeJson(version.publicExamples()))
        .complexityRubricJson(writeJson(version.complexityRubric()))
        .languageSpecsJson(writeJson(version.languages()))
        .publicTestsJson(writeJson(version.publicTests()))
        .hiddenTestsJson(writeJson(version.hiddenTests()))
        .contentHash(hash(writeJson(version)))
        .enabled(Boolean.TRUE.equals(version.enabled()))
        .javaEnabled(javaEnabled)
        .pythonEnabled(pythonEnabled)
        .createdAt(now)
        .updatedAt(now)
        .build());
  }

  private boolean enabled(
      AlgorithmCatalogContent.ProblemVersionDefinition version,
      CodingLanguage language
  ) {
    return version.languages().stream()
        .anyMatch(spec -> spec.language() == language && Boolean.TRUE.equals(spec.enabled()));
  }

  private String hash(String value) {
    return fileHashService.calculateHash(value.getBytes(StandardCharsets.UTF_8));
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化算法题库失败", e);
    }
  }

  record LoadedCatalog(AlgorithmCatalogContent content) {
  }

  public record ImportReport(
      String contentVersion,
      boolean alreadyImported,
      int problemCount,
      int enabledCount
  ) {
  }
}
