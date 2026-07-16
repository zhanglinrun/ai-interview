package com.linrun.interview.modules.interview.topic;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.capability.dto.CapabilityAtomDTO;
import com.linrun.interview.modules.capability.dto.CapabilityTemplateDTO;
import com.linrun.interview.modules.capability.model.JobTrack;
import com.linrun.interview.modules.capability.service.CapabilityCatalogService;
import com.linrun.interview.modules.interview.topic.InterviewTopic.Category;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 把版本化能力模板投影成旧文字面试和中心编排器可消费的主题目录。
 *
 * <p>唯一事实源是 capability catalog；本类不扫描 Markdown，也不加载 Persona 或工具指令。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewTopicCatalog {

  public static final String CUSTOM_TOPIC_ID = "custom";

  private static final int MAX_CATEGORY_KEY_LENGTH = 64;
  private static final int MAX_CATEGORY_LABEL_LENGTH = 64;
  private static final Map<String, JobTrack> TOPIC_TRACKS = Map.of(
      "java-backend", JobTrack.JAVA_BACKEND,
      "ai-rag-agent", JobTrack.AI_RAG_AGENT,
      "ai-agent-dev", JobTrack.AI_RAG_AGENT);

  private final CapabilityCatalogService capabilityCatalogService;

  public List<InterviewTopic> listTopics() {
    return capabilityCatalogService.listPublishedTemplates().stream()
        .map(this::toTopic)
        .toList();
  }

  public InterviewTopic getTopic(String topicId) {
    JobTrack track = TOPIC_TRACKS.get(topicId);
    if (track == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "未找到面试主题: " + topicId);
    }
    return toTopic(capabilityCatalogService.getPublishedTemplate(track));
  }

  public InterviewTopic buildCustomTopic(List<Category> customCategories, String jdText) {
    List<Category> categories = customCategories == null
        ? List.of()
        : customCategories.stream()
            .filter(category -> category != null
                && category.key() != null
                && category.label() != null)
            .map(category -> new Category(
                sanitizeKey(category.key()),
                sanitizeLabel(category.label()),
                normalizePriority(category.priority()),
                category.definitionVersion()))
            .toList();
    return new InterviewTopic(
        CUSTOM_TOPIC_ID,
        "JD 自定义面试",
        "基于当前职位描述冻结的考察范围",
        categories,
        false,
        jdText,
        null,
        null);
  }

  public Map<String, Integer> calculateAllocation(
      List<Category> categories,
      int totalQuestions
  ) {
    List<Category> safeCategories = categories == null ? List.of() : categories;
    List<Category> required = new ArrayList<>();
    List<Category> optional = new ArrayList<>();
    for (Category category : safeCategories) {
      if ("CORE".equals(normalizePriority(category.priority()))) {
        required.add(category);
      } else {
        optional.add(category);
      }
    }

    Map<String, Integer> allocation = new LinkedHashMap<>();
    int remaining = Math.max(totalQuestions, 0);
    remaining = allocateOne(required, allocation, remaining);
    remaining = allocateOne(optional, allocation, remaining);

    List<Category> rotation = new ArrayList<>(required);
    rotation.addAll(optional);
    while (remaining > 0 && !rotation.isEmpty()) {
      for (Category category : rotation) {
        if (remaining == 0) {
          break;
        }
        allocation.merge(category.key(), 1, Integer::sum);
        remaining--;
      }
    }
    rotation.forEach(category -> allocation.putIfAbsent(category.key(), 0));
    return allocation;
  }

  public String buildAllocationDescription(
      Map<String, Integer> allocation,
      List<Category> categories
  ) {
    StringBuilder result = new StringBuilder();
    for (Category category : categories == null ? List.<Category>of() : categories) {
      int count = allocation.getOrDefault(category.key(), 0);
      if (count > 0) {
        result.append("| ")
            .append(category.label())
            .append(" | ")
            .append(count)
            .append(" 题 | ")
            .append(normalizePriority(category.priority()))
            .append(" |\n");
      }
    }
    return result.toString();
  }

  private int allocateOne(
      List<Category> categories,
      Map<String, Integer> allocation,
      int remaining
  ) {
    int current = remaining;
    for (Category category : categories) {
      if (current == 0) {
        break;
      }
      allocation.put(category.key(), 1);
      current--;
    }
    return current;
  }

  private InterviewTopic toTopic(CapabilityTemplateDTO template) {
    String id = switch (template.jobTrack()) {
      case JAVA_BACKEND -> "java-backend";
      case AI_RAG_AGENT -> "ai-rag-agent";
    };
    String name = switch (template.jobTrack()) {
      case JAVA_BACKEND -> "Java 后端";
      case AI_RAG_AGENT -> "AI / RAG Agent";
    };
    List<Category> categories = template.capabilities().stream()
        .map(this::toCategory)
        .toList();
    return new InterviewTopic(
        id,
        name,
        "版本化岗位能力模板 " + template.templateCode() + "@" + template.version(),
        categories,
        true,
        null,
        template.templateCode(),
        template.version());
  }

  private Category toCategory(CapabilityAtomDTO atom) {
    String priority = atom.minimumCoverage() != null && atom.minimumCoverage() > 0
        ? "CORE" : "NORMAL";
    return new Category(atom.atomId(), atom.name(), priority, atom.atomVersion());
  }

  private String normalizePriority(String priority) {
    return "CORE".equalsIgnoreCase(priority) || "ALWAYS_ONE".equalsIgnoreCase(priority)
        ? "CORE" : "NORMAL";
  }

  private String sanitizeKey(String key) {
    String normalized = key.strip().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
    if (normalized.isBlank()) {
      return "UNKNOWN";
    }
    if (!Character.isLetter(normalized.charAt(0))) {
      normalized = "CAT_" + normalized;
    }
    return normalized.substring(0, Math.min(normalized.length(), MAX_CATEGORY_KEY_LENGTH));
  }

  private String sanitizeLabel(String label) {
    String normalized = label.strip().replaceAll("[\\r\\n]+", " ");
    if (normalized.isBlank()) {
      return "未命名";
    }
    return normalized.substring(0, Math.min(normalized.length(), MAX_CATEGORY_LABEL_LENGTH));
  }
}
