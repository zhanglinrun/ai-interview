package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.service.AgentOrchestrationProperties;
import com.linrun.interview.business.mapper.CandidateMemoryMapper;
import com.linrun.interview.business.vo.InterviewQuestionDTO;
import com.linrun.interview.business.vo.InterviewReportDTO;
import com.linrun.interview.business.vo.InterviewReportDTO.QuestionEvaluation;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨场长期记忆：评估分的确定性投影，不是对话摘要，也不是通用项目事实库。
 *
 * <p>评估结果已经包含逐题分数和反馈，因此这里直接把每道已回答问题沉淀为观测，
 * 不再额外调用一次 LLM 从报告中二次抽取自由文本。版本化能力模板的原子 ID 跨会话稳定，
 * Agent 动态题沿用题目上的 capabilityAtomId；旧会话按历史主题字段 + type 兼容映射。
 */
@Slf4j
@Service
public class CandidateMemoryService
    extends ServiceImpl<CandidateMemoryMapper, CandidateMemoryEntity> {

  private static final int MAX_OBSERVATIONS_PER_SESSION = 20;
  private static final int MAX_TOPIC_LENGTH = 128;
  private static final int MAX_EVIDENCE_LENGTH = 500;
  private static final int STRENGTH_SCORE = 75;
  private static final int WEAKNESS_SCORE = 60;

  private final CandidateMemoryMapper candidateMemoryMapper;
  private final ObjectMapper objectMapper;
  private final AgentOrchestrationProperties properties;

  public CandidateMemoryService(
      CandidateMemoryMapper candidateMemoryMapper,
      ObjectMapper objectMapper,
      AgentOrchestrationProperties properties) {
    this.candidateMemoryMapper = candidateMemoryMapper;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.baseMapper = candidateMemoryMapper;
  }

  /**
   * 将逐题评估沉淀为能力观测。
   *
   * <p>该方法故意不吞掉数据库异常：评估消费者会借助 MQ 重试，并从已落库报告重建输入。
   * 每条观测由 {@code sessionId + questionIndex} 唯一约束保护，部分成功后重试也不会重复写入。
   */
  public void extractAndSave(InterviewSessionEntity session, InterviewReportDTO report,
                             List<InterviewQuestionDTO> questions) {
    if (!properties.getCandidateMemory().isEnabled() || session == null || report == null
        || questions == null || questions.isEmpty()) {
      return;
    }
    Map<Integer, QuestionEvaluation> evaluationByIndex = report.questionDetails() == null
        ? Map.of()
        : report.questionDetails().stream().collect(Collectors.toMap(
            QuestionEvaluation::questionIndex, evaluation -> evaluation, (left, right) -> left));
    Set<Integer> existingIndexes = candidateMemoryMapper.selectList(
            Wrappers.<CandidateMemoryEntity>lambdaQuery()
                .eq(CandidateMemoryEntity::getSessionId, session.getSessionId()))
        .stream()
        .map(CandidateMemoryEntity::getQuestionIndex)
        .filter(java.util.Objects::nonNull)
        .collect(Collectors.toCollection(HashSet::new));

    int saved = 0;
    LocalDateTime now = LocalDateTime.now();
    for (InterviewQuestionDTO question : questions) {
      if (saved >= MAX_OBSERVATIONS_PER_SESSION
          || existingIndexes.contains(question.questionIndex())) {
        continue;
      }
      QuestionEvaluation evaluation = evaluationByIndex.get(question.questionIndex());
      if (evaluation == null || evaluation.userAnswer() == null
          || evaluation.userAnswer().isBlank() || evaluation.score() == null) {
        continue;
      }
      int score = Math.max(0, Math.min(100, evaluation.score()));
      CandidateMemoryEntity entity = CandidateMemoryEntity.builder()
          .userId(session.getUserId())
          .skillId(session.getSkillId())
          .capabilityAtomId(resolveCapabilityAtomId(session.getSkillId(), question))
          .topic(resolveTopic(question))
          .kind(kindForScore(score))
          .questionIndex(question.questionIndex())
          .masteryScore(score)
          .evidence(truncate(evaluation.feedback(), MAX_EVIDENCE_LENGTH))
          .evidenceIdsJson(toJsonQuietly(question.evidenceIds()))
          .sessionId(session.getSessionId())
          .createdAt(now)
          .build();
      try {
        save(entity);
        existingIndexes.add(question.questionIndex());
        saved++;
      } catch (DuplicateKeyException e) {
        // 查询与插入之间可能有并发重投，唯一键冲突等价于该题已经完成。
        existingIndexes.add(question.questionIndex());
        log.debug("能力观测已存在，按幂等成功处理: sessionId={}, questionIndex={}",
            session.getSessionId(), question.questionIndex());
      }
    }
    log.info("能力观测沉淀完成: sessionId={}, userId={}, saved={}",
        session.getSessionId(), session.getUserId(), saved);
  }

  /** 非关键调用方可选择静默降级；评估消费者必须调用严格版本以获得重试语义。 */
  public void extractAndSaveQuietly(InterviewSessionEntity session, InterviewReportDTO report,
                                    List<InterviewQuestionDTO> questions) {
    try {
      extractAndSave(session, report, questions);
    } catch (Exception e) {
      String sessionId = session == null ? null : session.getSessionId();
      log.warn("能力观测沉淀失败（静默降级）: sessionId={}", sessionId, e);
    }
  }

  /**
   * 组装 Planner 使用的跨场长期记忆。只有跨会话、足量观测的优势才标为“已验证”，
   * 避免一次高分就让 Planner 永久跳过该能力。
   */
  public String buildMemorySection(Long userId, String skillId) {
    if (!properties.getCandidateMemory().isEnabled() || userId == null) {
      return "";
    }
    try {
      int maxEntries = Math.max(1, properties.getCandidateMemory().getMaxEntries());
      List<CandidateMemoryProfileDTO> profiles = getProfile(userId, skillId);
      if (profiles.isEmpty()) {
        return "";
      }
      StringBuilder sb = new StringBuilder(
          "长期记忆（跨场能力观测。只有‘已验证·掌握’可降低题量，待复测项仍需抽样验证）：\n");
      profiles.stream().limit(maxEntries).forEach(profile -> {
        sb.append("- [").append(displayMastery(profile.masteryLevel()))
            .append(" · ").append(displayVerification(profile.verificationState()))
            .append("] ").append(profile.topic());
        if (profile.averageScore() != null) {
          sb.append("（均分 ").append(profile.averageScore())
              .append("，").append(profile.sessionCount()).append(" 场/")
              .append(profile.observationCount()).append(" 次观测）");
        }
        if (profile.latestEvidence() != null && !profile.latestEvidence().isBlank()) {
          sb.append("：").append(profile.latestEvidence());
        }
        sb.append('\n');
      });
      return sb.toString();
    } catch (Exception e) {
      log.warn("加载长期记忆失败，跳过注入: userId={}", userId, e);
      return "";
    }
  }

  /** 按稳定能力原子聚合画像；旧自由文本数据退回按 topic 聚合。 */
  public List<CandidateMemoryProfileDTO> getProfile(Long userId, String skillId) {
    List<CandidateMemoryEntity> entries = candidateMemoryMapper.selectList(
        Wrappers.<CandidateMemoryEntity>lambdaQuery()
            .eq(CandidateMemoryEntity::getUserId, userId)
            .eq(skillId != null && !skillId.isBlank(), CandidateMemoryEntity::getSkillId, skillId)
            .orderByDesc(CandidateMemoryEntity::getCreatedAt)
            .last("LIMIT 300"));

    Map<String, List<CandidateMemoryEntity>> grouped = new LinkedHashMap<>();
    for (CandidateMemoryEntity entry : entries) {
      String groupKey = entry.getCapabilityAtomId() != null
              && !entry.getCapabilityAtomId().isBlank()
          ? entry.getCapabilityAtomId()
          : "legacy:" + normalizeTopic(entry.getTopic());
      grouped.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(entry);
    }

    return grouped.entrySet().stream()
        .map(group -> aggregateProfile(group.getKey(), group.getValue()))
        .sorted(Comparator
            .comparingInt((CandidateMemoryProfileDTO profile) ->
                masteryRank(profile.masteryLevel()))
            .thenComparing(CandidateMemoryProfileDTO::lastAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
        .toList();
  }

  private CandidateMemoryProfileDTO aggregateProfile(String groupKey,
                                                      List<CandidateMemoryEntity> entries) {
    CandidateMemoryEntity latest = entries.getFirst();
    List<Integer> scores = entries.stream()
        .map(CandidateMemoryEntity::getMasteryScore)
        .filter(java.util.Objects::nonNull)
        .toList();
    Integer averageScore = scores.isEmpty() ? null
        : (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));
    int weaknessCount = countKind(entries, CandidateMemoryEntity.KIND_WEAKNESS);
    int developingCount = countKind(entries, CandidateMemoryEntity.KIND_DEVELOPING);
    int strengthCount = countKind(entries, CandidateMemoryEntity.KIND_STRENGTH);
    int sessionCount = (int) entries.stream()
        .map(CandidateMemoryEntity::getSessionId)
        .filter(sessionId -> sessionId != null && !sessionId.isBlank())
        .distinct()
        .count();
    String masteryLevel = resolveMasteryLevel(averageScore, latest.getKind());
    String verificationState = sessionCount >= 2 && scores.size() >= 3
        ? "VERIFIED" : "PROVISIONAL";
    String confidenceLevel = "VERIFIED".equals(verificationState)
        ? "HIGH" : (scores.size() >= 2 ? "MEDIUM" : "LOW");
    String capabilityAtomId = groupKey.startsWith("legacy:") ? null : groupKey;

    return new CandidateMemoryProfileDTO(
        capabilityAtomId,
        latest.getTopic(),
        averageScore,
        entries.size(),
        sessionCount,
        masteryLevel,
        verificationState,
        confidenceLevel,
        weaknessCount,
        developingCount,
        strengthCount,
        latest.getKind(),
        latest.getEvidence(),
        parseEvidenceIds(latest.getEvidenceIdsJson()),
        latest.getSessionId(),
        latest.getCreatedAt());
  }

  private String resolveCapabilityAtomId(String skillId, InterviewQuestionDTO question) {
    if (question.capabilityAtomId() != null && !question.capabilityAtomId().isBlank()) {
      return question.capabilityAtomId();
    }
    String type = question.type() == null || question.type().isBlank()
        ? question.category() : question.type();
    if (type != null && (type.startsWith("template:") || type.startsWith("skill:"))) {
      return type;
    }
    return "topic:" + safeIdPart(skillId) + ":" + safeIdPart(type);
  }

  private String resolveTopic(InterviewQuestionDTO question) {
    String topic = firstNonBlank(question.topicSummary(), question.category(), question.type(), "综合能力");
    topic = topic.replace("（追问）", "").strip();
    return truncate(topic, MAX_TOPIC_LENGTH);
  }

  private String kindForScore(int score) {
    if (score >= STRENGTH_SCORE) {
      return CandidateMemoryEntity.KIND_STRENGTH;
    }
    if (score < WEAKNESS_SCORE) {
      return CandidateMemoryEntity.KIND_WEAKNESS;
    }
    return CandidateMemoryEntity.KIND_DEVELOPING;
  }

  private String resolveMasteryLevel(Integer averageScore, String latestKind) {
    if (averageScore != null) {
      if (averageScore >= STRENGTH_SCORE) {
        return "STRENGTH";
      }
      if (averageScore < WEAKNESS_SCORE) {
        return "WEAKNESS";
      }
      return "DEVELOPING";
    }
    if (CandidateMemoryEntity.KIND_STRENGTH.equals(latestKind)) {
      return "STRENGTH";
    }
    if (CandidateMemoryEntity.KIND_DEVELOPING.equals(latestKind)) {
      return "DEVELOPING";
    }
    return "WEAKNESS";
  }

  private int countKind(List<CandidateMemoryEntity> entries, String kind) {
    return (int) entries.stream().filter(entry -> kind.equals(entry.getKind())).count();
  }

  private int masteryRank(String masteryLevel) {
    return switch (masteryLevel) {
      case "WEAKNESS" -> 0;
      case "DEVELOPING" -> 1;
      default -> 2;
    };
  }

  private String displayMastery(String masteryLevel) {
    return switch (masteryLevel) {
      case "WEAKNESS" -> "薄弱";
      case "DEVELOPING" -> "发展中";
      default -> "掌握";
    };
  }

  private String displayVerification(String state) {
    return "VERIFIED".equals(state) ? "已验证" : "待复测";
  }

  private List<String> parseEvidenceIds(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    } catch (Exception e) {
      return List.of();
    }
  }

  private String toJsonQuietly(List<String> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(values);
    } catch (Exception e) {
      return null;
    }
  }

  private String normalizeTopic(String topic) {
    return topic == null ? "unknown" : topic.strip().toLowerCase(Locale.ROOT);
  }

  private String safeIdPart(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    String normalized = value.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-+|-+$)", "");
    return normalized.isBlank()
        ? "topic-" + Integer.toUnsignedString(value.hashCode(), 36)
        : normalized;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private String truncate(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    String normalized = value.strip();
    return normalized.length() <= maxLength
        ? normalized : normalized.substring(0, maxLength);
  }

  public record CandidateMemoryProfileDTO(
      String capabilityAtomId,
      String topic,
      Integer averageScore,
      int observationCount,
      int sessionCount,
      String masteryLevel,
      String verificationState,
      String confidenceLevel,
      int weaknessCount,
      int developingCount,
      int strengthCount,
      String latestKind,
      String latestEvidence,
      List<String> latestEvidenceIds,
      String lastSessionId,
      LocalDateTime lastAt
  ) {}
}
