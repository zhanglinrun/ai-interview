package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceMetadata;
import com.linrun.interview.business.vo.CapabilityCatalogContent;
import com.linrun.interview.business.vo.CapabilityCatalogContent.AtomContent;
import com.linrun.interview.business.vo.CapabilityCatalogContent.PlatformKnowledgeContent;
import com.linrun.interview.business.vo.CapabilityCatalogContent.QuestionTemplateContent;
import com.linrun.interview.business.vo.CapabilityCatalogContent.RubricContent;
import com.linrun.interview.business.vo.CapabilityCatalogContent.TemplateContent;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 在写库前完成内容文件的引用、权重、版本和 checksum 校验。 */
@Component
public class CapabilityContentValidator {

  public static final String SUPPORTED_SCHEMA_VERSION = "1.0";
  private static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.0001");

  private final ObjectMapper objectMapper;

  public CapabilityContentValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ValidationReport validate(CapabilityCatalogContent content) {
    List<String> errors = new ArrayList<>();
    if (content == null) {
      return new ValidationReport(false, List.of("内容文件为空"), null, Counts.empty());
    }
    require(SUPPORTED_SCHEMA_VERSION.equals(content.schemaVersion()),
        "不支持的 schemaVersion: " + content.schemaVersion(), errors);
    require(hasText(content.contentVersion()), "contentVersion 不能为空", errors);
    require(content.source() != null && hasText(content.source().name())
        && hasText(content.source().locator()), "source.name/source.locator 不能为空", errors);
    validateDate(content.effectiveDate(), errors);

    List<AtomContent> atoms = safe(content.atoms());
    List<TemplateContent> templates = safe(content.templates());
    List<QuestionTemplateContent> questions = safe(content.questionTemplates());
    List<RubricContent> rubrics = safe(content.rubrics());
    List<PlatformKnowledgeContent> knowledge = safe(content.platformKnowledge());
    require(!atoms.isEmpty(), "至少需要一个能力原子", errors);
    require(!templates.isEmpty(), "至少需要一个岗位模板", errors);
    require(!questions.isEmpty(), "至少需要一个题型骨架", errors);
    require(!rubrics.isEmpty(), "至少需要一个 Rubric", errors);

    Set<String> atomKeys = validateAtoms(atoms, errors);
    Set<String> rubricKeys = validateRubrics(rubrics, errors);
    validateTemplates(templates, atomKeys, errors);
    validateQuestions(questions, atomKeys, rubricKeys, errors);
    validatePlatformKnowledge(knowledge, atoms, errors);

    String calculatedChecksum = calculateChecksum(content);
    require(("sha256:" + calculatedChecksum).equalsIgnoreCase(content.checksum()),
        "checksum 不匹配，应为 sha256:" + calculatedChecksum, errors);
    Counts counts = new Counts(atoms.size(), templates.size(), questions.size(),
        rubrics.size(), knowledge.size());
    return new ValidationReport(errors.isEmpty(), List.copyOf(errors), calculatedChecksum, counts);
  }

  /**
   * 对去掉 checksum 值、递归按字段名排序后的语义 JSON 计算 SHA-256，避免空白和属性顺序影响结果。
   */
  public String calculateChecksum(CapabilityCatalogContent content) {
    try {
      ObjectNode root = objectMapper.valueToTree(content);
      root.put("checksum", "");
      byte[] canonical = objectMapper.writeValueAsBytes(sortNode(root));
      return sha256Hex(canonical);
    } catch (Exception e) {
      throw new IllegalStateException("计算能力内容 checksum 失败", e);
    }
  }

  private Set<String> validateAtoms(List<AtomContent> atoms, List<String> errors) {
    Set<String> keys = new HashSet<>();
    for (AtomContent atom : atoms) {
      if (atom == null || !hasText(atom.atomId()) || !hasText(atom.version())) {
        errors.add("能力原子的 atomId/version 不能为空");
        continue;
      }
      require(keys.add(key(atom.atomId(), atom.version())),
          "能力原子重复: " + key(atom.atomId(), atom.version()), errors);
      require(atom.atomId().matches("[A-Z][A-Z0-9_]{2,63}"),
          "atomId 格式非法: " + atom.atomId(), errors);
      require(hasText(atom.name()) && hasText(atom.description()) && hasText(atom.capabilityDomain()),
          "能力原子缺少名称/说明/领域: " + atom.atomId(), errors);
      require(atom.jobTracks() != null && !atom.jobTracks().isEmpty(),
          "能力原子未绑定岗位: " + atom.atomId(), errors);
    }
    for (AtomContent atom : atoms) {
      if (atom != null && hasText(atom.parentAtomId())) {
        boolean parentExists = atoms.stream().anyMatch(candidate -> candidate != null
            && atom.parentAtomId().equals(candidate.atomId()));
        require(parentExists, "父能力原子不存在: " + atom.parentAtomId(), errors);
      }
    }
    return keys;
  }

  private Set<String> validateRubrics(List<RubricContent> rubrics, List<String> errors) {
    Set<String> keys = new HashSet<>();
    for (RubricContent rubric : rubrics) {
      if (rubric == null || !hasText(rubric.rubricCode()) || !hasText(rubric.version())) {
        errors.add("Rubric code/version 不能为空");
        continue;
      }
      require(keys.add(key(rubric.rubricCode(), rubric.version())),
          "Rubric 重复: " + key(rubric.rubricCode(), rubric.version()), errors);
      require(rubric.status() != null, "Rubric 状态不能为空: " + rubric.rubricCode(), errors);
      require(rubric.dimensions() != null && !rubric.dimensions().isEmpty(),
          "Rubric 维度不能为空: " + rubric.rubricCode(), errors);
      if (rubric.dimensions() != null) {
        Set<String> dimensionCodes = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var dimension : rubric.dimensions()) {
          if (dimension == null || !hasText(dimension.code()) || dimension.weight() == null) {
            errors.add("Rubric 维度 code/weight 不能为空: " + rubric.rubricCode());
            continue;
          }
          require(dimensionCodes.add(dimension.code()),
              "Rubric 维度重复: " + rubric.rubricCode() + "/" + dimension.code(), errors);
          require(hasText(dimension.name()) && hasText(dimension.criteria()),
              "Rubric 维度缺少名称或标准: " + dimension.code(), errors);
          require(validWeight(dimension.weight()), "Rubric 权重非法: " + dimension.code(), errors);
          total = total.add(dimension.weight());
        }
        require(isOne(total), "Rubric 权重之和必须为 1: " + rubric.rubricCode(), errors);
      }
    }
    return keys;
  }

  private void validateTemplates(
      List<TemplateContent> templates,
      Set<String> atomKeys,
      List<String> errors
  ) {
    Set<String> templateKeys = new HashSet<>();
    Set<Object> tracks = new HashSet<>();
    for (TemplateContent template : templates) {
      if (template == null || !hasText(template.templateCode()) || !hasText(template.version())) {
        errors.add("岗位模板 code/version 不能为空");
        continue;
      }
      require(templateKeys.add(key(template.templateCode(), template.version())),
          "岗位模板重复: " + key(template.templateCode(), template.version()), errors);
      require(template.jobTrack() != null && tracks.add(template.jobTrack()),
          "每个岗位方向只能有一个当前基线模板: " + template.jobTrack(), errors);
      require(template.status() != null, "岗位模板状态不能为空: " + template.templateCode(), errors);
      require(template.capabilities() != null && !template.capabilities().isEmpty(),
          "岗位模板能力不能为空: " + template.templateCode(), errors);
      if (template.capabilities() == null) {
        continue;
      }
      Set<String> templateAtoms = new HashSet<>();
      BigDecimal total = BigDecimal.ZERO;
      for (var capability : template.capabilities()) {
        if (capability == null) {
          errors.add("岗位模板包含空能力: " + template.templateCode());
          continue;
        }
        String atomKey = key(capability.atomId(), capability.atomVersion());
        require(atomKeys.contains(atomKey), "岗位模板引用未知能力: " + atomKey, errors);
        require(templateAtoms.add(atomKey), "岗位模板能力重复: " + atomKey, errors);
        require(validWeight(capability.defaultWeight()), "岗位模板权重非法: " + atomKey, errors);
        require(capability.minimumCoverage() != null && capability.minimumCoverage() >= 0,
            "minimumCoverage 非法: " + atomKey, errors);
        require(capability.questionTypes() != null && !capability.questionTypes().isEmpty(),
            "questionTypes 不能为空: " + atomKey, errors);
        if (capability.defaultWeight() != null) {
          total = total.add(capability.defaultWeight());
        }
      }
      require(isOne(total), "岗位模板权重之和必须为 1: " + template.templateCode(), errors);
    }
  }

  private void validateQuestions(
      List<QuestionTemplateContent> questions,
      Set<String> atomKeys,
      Set<String> rubricKeys,
      List<String> errors
  ) {
    Set<String> questionKeys = new HashSet<>();
    for (QuestionTemplateContent question : questions) {
      if (question == null || !hasText(question.questionCode()) || !hasText(question.version())) {
        errors.add("题型骨架 code/version 不能为空");
        continue;
      }
      require(questionKeys.add(key(question.questionCode(), question.version())),
          "题型骨架重复: " + key(question.questionCode(), question.version()), errors);
      require(atomKeys.contains(key(question.atomId(), question.atomVersion())),
          "题型骨架引用未知能力: " + question.questionCode(), errors);
      require(rubricKeys.contains(key(question.rubricCode(), question.rubricVersion())),
          "题型骨架引用未知 Rubric: " + question.questionCode(), errors);
      require(question.status() != null && hasText(question.difficulty())
          && hasText(question.stage()) && hasText(question.promptSkeleton()),
          "题型骨架字段不完整: " + question.questionCode(), errors);
    }
  }

  private void validatePlatformKnowledge(
      List<PlatformKnowledgeContent> knowledge,
      List<AtomContent> atoms,
      List<String> errors
  ) {
    Set<String> evidenceIds = new HashSet<>();
    Set<String> knownAtomIds = new HashSet<>();
    atoms.forEach(atom -> knownAtomIds.add(atom.atomId()));
    for (PlatformKnowledgeContent item : knowledge) {
      if (item == null) {
        errors.add("平台资料清单包含空项");
        continue;
      }
      require(evidenceIds.add(item.evidenceId()), "平台 evidenceId 重复: " + item.evidenceId(), errors);
      try {
        new EvidenceMetadata(
            DataDomain.PLATFORM_OWNER_USER_ID,
            DataDomain.PLATFORM,
            item.resourceId(),
            item.resourceVersion(),
            item.evidenceId(),
            item.contentHash(),
            item.sourceType(),
            item.sourceLocator());
      } catch (IllegalArgumentException e) {
        errors.add("平台资料元数据非法: " + e.getMessage());
      }
      require(hasText(item.title()) && hasText(item.summary()),
          "平台资料标题/摘要不能为空: " + item.evidenceId(), errors);
      require(hasText(item.contentHash()) && item.contentHash().matches("[a-f0-9]{64}"),
          "平台资料 contentHash 必须为小写 SHA-256: " + item.evidenceId(), errors);
      if (hasText(item.summary()) && hasText(item.contentHash())) {
        require(sha256Hex(item.summary().getBytes(StandardCharsets.UTF_8))
                .equals(item.contentHash()),
            "平台资料 contentHash 与审核摘要不一致: " + item.evidenceId(), errors);
      }
      require(isHttpUrl(item.sourceLocator()),
          "平台资料 sourceLocator 必须为 HTTP(S) 官方来源: " + item.evidenceId(), errors);
      for (String atomId : safe(item.capabilityAtomIds())) {
        require(knownAtomIds.contains(atomId), "平台资料引用未知能力: " + atomId, errors);
      }
    }
  }

  private JsonNode sortNode(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = objectMapper.createObjectNode();
      List<String> names = new ArrayList<>();
      Iterator<String> fieldNames = node.fieldNames();
      fieldNames.forEachRemaining(names::add);
      names.sort(Comparator.naturalOrder());
      names.forEach(name -> sorted.set(name, sortNode(node.get(name))));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode array = objectMapper.createArrayNode();
      node.forEach(child -> array.add(sortNode(child)));
      return array;
    }
    return node;
  }

  private String sha256Hex(byte[] value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        hex.append(String.format("%02x", item));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("计算 SHA-256 失败", e);
    }
  }

  private boolean isHttpUrl(String value) {
    if (!hasText(value)) {
      return false;
    }
    try {
      URI uri = URI.create(value);
      return uri.getHost() != null
          && ("http".equalsIgnoreCase(uri.getScheme())
          || "https".equalsIgnoreCase(uri.getScheme()));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private void validateDate(String value, List<String> errors) {
    try {
      LocalDate.parse(value);
    } catch (DateTimeParseException | NullPointerException e) {
      errors.add("effectiveDate 必须为 ISO 日期");
    }
  }

  private boolean validWeight(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0
        && value.compareTo(BigDecimal.ONE) <= 0;
  }

  private boolean isOne(BigDecimal value) {
    return value.subtract(BigDecimal.ONE).abs().compareTo(WEIGHT_TOLERANCE) <= 0;
  }

  private String key(String id, String version) {
    return String.valueOf(id) + "@" + String.valueOf(version);
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private void require(boolean condition, String error, List<String> errors) {
    if (!condition) {
      errors.add(error);
    }
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  public record ValidationReport(
      boolean valid,
      List<String> errors,
      String calculatedChecksum,
      Counts counts
  ) {
  }

  public record Counts(int atoms, int templates, int questionTemplates, int rubrics, int knowledgeItems) {
    static Counts empty() {
      return new Counts(0, 0, 0, 0, 0);
    }
  }
}
