package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.business.mapper.CapabilityAtomDefinitionMapper;
import com.linrun.interview.business.mapper.CapabilityContentImportMapper;
import com.linrun.interview.business.mapper.CapabilityTemplateMapper;
import com.linrun.interview.business.mapper.EvaluationRubricMapper;
import com.linrun.interview.business.mapper.PlatformKnowledgeManifestMapper;
import com.linrun.interview.business.mapper.QuestionTemplateMapper;
import com.linrun.interview.business.mapper.TemplateCapabilityMapper;
import com.linrun.interview.business.entity.CapabilityAtomDefinitionEntity;
import com.linrun.interview.business.vo.CapabilityCatalogContent;
import com.linrun.interview.business.entity.CapabilityContentImportEntity;
import com.linrun.interview.business.entity.CapabilityTemplateEntity;
import com.linrun.interview.business.constant.CatalogStatus;
import com.linrun.interview.business.entity.EvaluationRubricEntity;
import com.linrun.interview.business.entity.PlatformKnowledgeManifestEntity;
import com.linrun.interview.business.entity.QuestionTemplateEntity;
import com.linrun.interview.business.entity.TemplateCapabilityEntity;
import com.linrun.interview.business.service.CapabilityContentValidator.ValidationReport;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 版本化能力内容的校验、dry-run 与幂等导入。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentImportService {

  static final String CATALOG_RESOURCE = "capability-content/catalog-v1.json";

  private final ObjectMapper objectMapper;
  private final CapabilityContentValidator validator;
  private final CapabilityContentImportMapper importMapper;
  private final CapabilityTemplateMapper templateMapper;
  private final CapabilityAtomDefinitionMapper atomMapper;
  private final TemplateCapabilityMapper templateCapabilityMapper;
  private final QuestionTemplateMapper questionTemplateMapper;
  private final EvaluationRubricMapper rubricMapper;
  private final PlatformKnowledgeManifestMapper knowledgeManifestMapper;

  public ValidationReport validateClasspathCatalog() {
    return validator.validate(loadClasspathCatalog());
  }

  public ImportReport dryRunClasspathCatalog() {
    CapabilityCatalogContent content = loadClasspathCatalog();
    ValidationReport validation = validator.validate(content);
    CapabilityContentImportEntity imported = validation.valid()
        ? findImport(content.contentVersion()) : null;
    boolean alreadyImported = imported != null
        && imported.getChecksum().equalsIgnoreCase(content.checksum());
    String conflict = imported != null && !alreadyImported
        ? "contentVersion 已存在但 checksum 不同，已发布版本禁止覆盖" : null;
    return new ImportReport(
        content.contentVersion(), validation, alreadyImported, true, conflict);
  }

  @Transactional(rollbackFor = Exception.class)
  public ImportReport importClasspathCatalog() {
    CapabilityCatalogContent content = loadClasspathCatalog();
    ValidationReport validation = validator.validate(content);
    if (!validation.valid()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "能力内容校验失败: " + String.join("；", validation.errors()));
    }

    CapabilityContentImportEntity imported = findImport(content.contentVersion());
    if (imported != null) {
      if (imported.getChecksum().equalsIgnoreCase(content.checksum())) {
        return new ImportReport(content.contentVersion(), validation, true, false, null);
      }
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "contentVersion 已存在但 checksum 不同，已发布版本禁止覆盖");
    }

    ensureNoVersionConflicts(content);
    LocalDateTime now = LocalDateTime.now();
    String catalogHash = validation.calculatedChecksum();
    Map<String, Long> atomIds = insertAtoms(content, catalogHash, now);
    insertRubrics(content, catalogHash, now);
    insertTemplates(content, catalogHash, atomIds, now);
    insertQuestions(content, catalogHash, atomIds, now);
    insertKnowledgeManifest(content, now);
    importMapper.insert(CapabilityContentImportEntity.builder()
        .schemaVersion(content.schemaVersion())
        .contentVersion(content.contentVersion())
        .sourceName(content.source().name())
        .sourceLocator(content.source().locator())
        .checksum(content.checksum())
        .status("IMPORTED")
        .importedAt(now)
        .build());
    log.info("能力内容导入完成: version={}, atoms={}, templates={}, questions={}, rubrics={}",
        content.contentVersion(), content.atoms().size(), content.templates().size(),
        content.questionTemplates().size(), content.rubrics().size());
    return new ImportReport(content.contentVersion(), validation, false, false, null);
  }

  CapabilityCatalogContent loadClasspathCatalog() {
    ClassPathResource resource = new ClassPathResource(CATALOG_RESOURCE);
    try (var input = resource.getInputStream()) {
      return objectMapper.readValue(input, CapabilityCatalogContent.class);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR,
          "读取能力内容文件失败: " + CATALOG_RESOURCE, e);
    }
  }

  private CapabilityContentImportEntity findImport(String contentVersion) {
    return importMapper.selectOne(Wrappers.<CapabilityContentImportEntity>lambdaQuery()
        .eq(CapabilityContentImportEntity::getContentVersion, contentVersion));
  }

  private void ensureNoVersionConflicts(CapabilityCatalogContent content) {
    for (var atom : content.atoms()) {
      if (atomMapper.selectCount(Wrappers.<CapabilityAtomDefinitionEntity>lambdaQuery()
          .eq(CapabilityAtomDefinitionEntity::getAtomId, atom.atomId())
          .eq(CapabilityAtomDefinitionEntity::getVersion, atom.version())) > 0) {
        throw versionConflict("能力原子", atom.atomId(), atom.version());
      }
    }
    for (var template : content.templates()) {
      if (templateMapper.selectCount(Wrappers.<CapabilityTemplateEntity>lambdaQuery()
          .eq(CapabilityTemplateEntity::getTemplateCode, template.templateCode())
          .eq(CapabilityTemplateEntity::getVersion, template.version())) > 0) {
        throw versionConflict("能力模板", template.templateCode(), template.version());
      }
    }
    for (var question : content.questionTemplates()) {
      if (questionTemplateMapper.selectCount(Wrappers.<QuestionTemplateEntity>lambdaQuery()
          .eq(QuestionTemplateEntity::getQuestionCode, question.questionCode())
          .eq(QuestionTemplateEntity::getVersion, question.version())) > 0) {
        throw versionConflict("题型骨架", question.questionCode(), question.version());
      }
    }
    for (var rubric : content.rubrics()) {
      if (rubricMapper.selectCount(Wrappers.<EvaluationRubricEntity>lambdaQuery()
          .eq(EvaluationRubricEntity::getRubricCode, rubric.rubricCode())
          .eq(EvaluationRubricEntity::getVersion, rubric.version())) > 0) {
        throw versionConflict("Rubric", rubric.rubricCode(), rubric.version());
      }
    }
  }

  private BusinessException versionConflict(String type, String code, String version) {
    return new BusinessException(ErrorCode.BAD_REQUEST,
        type + "版本已存在，禁止原地覆盖: " + code + "@" + version);
  }

  private Map<String, Long> insertAtoms(
      CapabilityCatalogContent content,
      String catalogHash,
      LocalDateTime now
  ) {
    Map<String, Long> ids = new HashMap<>();
    for (var atom : content.atoms()) {
      CapabilityAtomDefinitionEntity entity = CapabilityAtomDefinitionEntity.builder()
          .atomId(atom.atomId())
          .version(atom.version())
          .name(atom.name())
          .description(atom.description())
          .capabilityDomain(atom.capabilityDomain())
          .jobTracksJson(toJson(atom.jobTracks()))
          .parentAtomId(atom.parentAtomId())
          .contentHash(catalogHash)
          .createdAt(now)
          .build();
      atomMapper.insert(entity);
      ids.put(key(atom.atomId(), atom.version()), entity.getId());
    }
    return ids;
  }

  private void insertRubrics(
      CapabilityCatalogContent content,
      String catalogHash,
      LocalDateTime now
  ) {
    for (var rubric : content.rubrics()) {
      rubricMapper.insert(EvaluationRubricEntity.builder()
          .rubricCode(rubric.rubricCode())
          .version(rubric.version())
          .status(rubric.status())
          .dimensionsJson(toJson(rubric.dimensions()))
          .contentHash(catalogHash)
          .createdAt(now)
          .build());
    }
  }

  private void insertTemplates(
      CapabilityCatalogContent content,
      String catalogHash,
      Map<String, Long> atomIds,
      LocalDateTime now
  ) {
    for (var template : content.templates()) {
      CapabilityTemplateEntity entity = CapabilityTemplateEntity.builder()
          .templateCode(template.templateCode())
          .jobTrack(template.jobTrack())
          .version(template.version())
          .status(template.status())
          .sourceName(content.source().name())
          .sourceLocator(content.source().locator())
          .contentHash(catalogHash)
          .effectiveDate(LocalDate.parse(content.effectiveDate()))
          .createdAt(now)
          .updatedAt(now)
          .build();
      templateMapper.insert(entity);
      for (var capability : template.capabilities()) {
        templateCapabilityMapper.insert(TemplateCapabilityEntity.builder()
            .templateId(entity.getId())
            .atomDefinitionId(atomIds.get(key(capability.atomId(), capability.atomVersion())))
            .defaultWeight(capability.defaultWeight())
            .minimumCoverage(capability.minimumCoverage())
            .questionTypesJson(toJson(capability.questionTypes()))
            .build());
      }
    }
  }

  private void insertQuestions(
      CapabilityCatalogContent content,
      String catalogHash,
      Map<String, Long> atomIds,
      LocalDateTime now
  ) {
    for (var question : content.questionTemplates()) {
      questionTemplateMapper.insert(QuestionTemplateEntity.builder()
          .questionCode(question.questionCode())
          .version(question.version())
          .status(question.status())
          .atomDefinitionId(atomIds.get(key(question.atomId(), question.atomVersion())))
          .difficulty(question.difficulty())
          .stage(question.stage())
          .promptSkeleton(question.promptSkeleton())
          .rubricCode(question.rubricCode())
          .rubricVersion(question.rubricVersion())
          .contentHash(catalogHash)
          .createdAt(now)
          .build());
    }
  }

  private void insertKnowledgeManifest(CapabilityCatalogContent content, LocalDateTime now) {
    for (var item : content.platformKnowledge()) {
      knowledgeManifestMapper.insert(PlatformKnowledgeManifestEntity.builder()
          .ownerUserId(DataDomain.PLATFORM_OWNER_USER_ID)
          .dataDomain(DataDomain.PLATFORM)
          .evidenceId(item.evidenceId())
          .resourceId(item.resourceId())
          .resourceVersion(item.resourceVersion())
          .title(item.title())
          .summary(item.summary())
          .sourceType(item.sourceType())
          .sourceLocator(item.sourceLocator())
          .contentHash(item.contentHash())
          .capabilityAtomIdsJson(toJson(item.capabilityAtomIds()))
          .status(CatalogStatus.PUBLISHED)
          .createdAt(now)
          .build());
    }
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化能力内容失败", e);
    }
  }

  private String key(String id, String version) {
    return id + "@" + version;
  }

  public record ImportReport(
      String contentVersion,
      ValidationReport validation,
      boolean alreadyImported,
      boolean dryRun,
      String conflict
  ) {
  }
}
