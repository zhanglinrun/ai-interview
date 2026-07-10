package com.linrun.interview.modules.interview.memory;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.ai.PromptTemplate;
import com.linrun.interview.common.ai.StructuredOutputInvoker;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.interview.agent.AgentOrchestrationProperties;
import com.linrun.interview.modules.interview.mapper.CandidateMemoryMapper;
import com.linrun.interview.modules.interview.model.InterviewReportDTO;
import com.linrun.interview.modules.interview.model.InterviewSessionEntity;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨会话候选人画像记忆（语义记忆层）。
 *
 * <p>面试评估完成后用 LLM 从评估报告抽取结构化记忆条目
 * {@code {topic, strength|weakness, evidence}} 落 candidate_memory 表；
 * Planner 生成大纲时注入该用户近 N 条画像（薄弱点加深追问、已掌握主题降低占比）。
 *
 * <p>抽取运行在评估消费者线程（无 UserContext），userId 从会话实体显式取；
 * 抽取失败只告警，不阻断评估主链路。
 */
@Service
public class CandidateMemoryService {

  private static final Logger log = LoggerFactory.getLogger(CandidateMemoryService.class);

  private static final int MAX_ENTRIES_PER_SESSION = 10;
  private static final int MAX_EVIDENCE_LENGTH = 500;
  private static final int MAX_REPORT_DETAIL_ITEMS = 20;

  private final CandidateMemoryMapper candidateMemoryMapper;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final LlmProviderRegistry llmProviderRegistry;
  private final AgentOrchestrationProperties properties;
  private final PromptTemplate extractSystemPrompt;

  private record MemoryEntryDTO(String topic, String kind, String evidence) {}

  private record MemoryEntriesDTO(List<MemoryEntryDTO> entries) {}

  public CandidateMemoryService(CandidateMemoryMapper candidateMemoryMapper,
                                StructuredOutputInvoker structuredOutputInvoker,
                                LlmProviderRegistry llmProviderRegistry,
                                AgentOrchestrationProperties properties,
                                ResourceLoader resourceLoader) throws IOException {
    this.candidateMemoryMapper = candidateMemoryMapper;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.llmProviderRegistry = llmProviderRegistry;
    this.properties = properties;
    this.extractSystemPrompt = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/agent/memory-extract-system.st")
        .getContentAsString(StandardCharsets.UTF_8));
  }

  /**
   * 从评估报告抽取画像条目并入库（失败静默，不阻断评估主链路）。
   */
  public void extractAndSaveQuietly(InterviewSessionEntity session, InterviewReportDTO report) {
    if (!properties.getCandidateMemory().isEnabled() || session == null || report == null) {
      return;
    }
    try {
      // 异步抽取无 UserContext：从会话实体取 userId，走该用户的 BYOK「我的模型」
      ChatModel chatModel = llmProviderRegistry.getUserChatModel(session.getUserId());
      MemoryEntriesDTO dto = structuredOutputInvoker.invoke(
          chatModel, extractSystemPrompt.render(), buildExtractionInput(report),
          MemoryEntriesDTO.class, ErrorCode.AI_SERVICE_ERROR,
          "画像抽取失败：", "候选人画像抽取", log);

      List<MemoryEntryDTO> entries = dto == null || dto.entries() == null
          ? List.of() : dto.entries();
      LocalDateTime now = LocalDateTime.now();
      int saved = 0;
      for (MemoryEntryDTO entry : entries) {
        if (entry == null || entry.topic() == null || entry.topic().isBlank()
            || !isValidKind(entry.kind())) {
          continue;
        }
        if (saved >= MAX_ENTRIES_PER_SESSION) {
          break;
        }
        candidateMemoryMapper.insert(CandidateMemoryEntity.builder()
            .userId(session.getUserId())
            .skillId(session.getSkillId())
            .topic(entry.topic().strip())
            .kind(entry.kind().strip().toLowerCase())
            .evidence(truncate(entry.evidence()))
            .sessionId(session.getSessionId())
            .createdAt(now)
            .build());
        saved++;
      }
      log.info("候选人画像抽取完成: sessionId={}, userId={}, 入库 {} 条",
          session.getSessionId(), session.getUserId(), saved);
    } catch (Exception e) {
      log.warn("候选人画像抽取失败（不阻断评估）: sessionId={}", session.getSessionId(), e);
    }
  }

  /**
   * 组装 Planner 大纲注入的画像段落；无画像或功能关闭时返回空字符串。
   */
  public String buildMemorySection(Long userId, String skillId) {
    if (!properties.getCandidateMemory().isEnabled() || userId == null) {
      return "";
    }
    try {
      int maxEntries = Math.max(1, properties.getCandidateMemory().getMaxEntries());
      List<CandidateMemoryEntity> entries = candidateMemoryMapper.selectList(
          Wrappers.<CandidateMemoryEntity>lambdaQuery()
              .eq(CandidateMemoryEntity::getUserId, userId)
              .eq(skillId != null && !skillId.isBlank(),
                  CandidateMemoryEntity::getSkillId, skillId)
              .orderByDesc(CandidateMemoryEntity::getCreatedAt)
              .last("LIMIT " + maxEntries * 3));
      if (entries.isEmpty()) {
        return "";
      }
      // 薄弱点优先注入，其次是掌握项（去重同 topic 取最新一条）
      Map<String, CandidateMemoryEntity> latestByTopic = new LinkedHashMap<>();
      entries.stream()
          .sorted((a, b) -> {
            boolean aWeak = CandidateMemoryEntity.KIND_WEAKNESS.equals(a.getKind());
            boolean bWeak = CandidateMemoryEntity.KIND_WEAKNESS.equals(b.getKind());
            return aWeak == bWeak ? 0 : (aWeak ? -1 : 1);
          })
          .forEach(e -> latestByTopic.putIfAbsent(e.getTopic(), e));

      StringBuilder sb = new StringBuilder("候选人历史画像（来自过往面试评估，供规划参考）：\n");
      latestByTopic.values().stream().limit(maxEntries).forEach(e ->
          sb.append("- [").append(CandidateMemoryEntity.KIND_WEAKNESS.equals(e.getKind())
                  ? "薄弱" : "掌握").append("] ")
              .append(e.getTopic()).append('：')
              .append(e.getEvidence() == null ? "" : e.getEvidence())
              .append('\n'));
      return sb.toString();
    } catch (Exception e) {
      log.warn("加载候选人画像失败，跳过注入: userId={}", userId, e);
      return "";
    }
  }

  /**
   * 画像查询（前端「我的薄弱点画像」）：按 topic 聚合。
   */
  public List<CandidateMemoryProfileDTO> getProfile(Long userId, String skillId) {
    List<CandidateMemoryEntity> entries = candidateMemoryMapper.selectList(
        Wrappers.<CandidateMemoryEntity>lambdaQuery()
            .eq(CandidateMemoryEntity::getUserId, userId)
            .eq(skillId != null && !skillId.isBlank(), CandidateMemoryEntity::getSkillId, skillId)
            .orderByDesc(CandidateMemoryEntity::getCreatedAt)
            .last("LIMIT 200"));

    Map<String, List<CandidateMemoryEntity>> grouped = new LinkedHashMap<>();
    for (CandidateMemoryEntity entry : entries) {
      grouped.computeIfAbsent(entry.getTopic(), k -> new java.util.ArrayList<>()).add(entry);
    }
    return grouped.entrySet().stream()
        .map(group -> {
          List<CandidateMemoryEntity> topicEntries = group.getValue();
          CandidateMemoryEntity latest = topicEntries.get(0);
          long weakness = topicEntries.stream()
              .filter(e -> CandidateMemoryEntity.KIND_WEAKNESS.equals(e.getKind())).count();
          long strength = topicEntries.size() - weakness;
          return new CandidateMemoryProfileDTO(
              group.getKey(), (int) weakness, (int) strength,
              latest.getKind(), latest.getEvidence(),
              latest.getSessionId(), latest.getCreatedAt());
        })
        .toList();
  }

  /**
   * 画像聚合条目（按 topic 分组）。
   */
  public record CandidateMemoryProfileDTO(
      String topic,
      int weaknessCount,
      int strengthCount,
      String latestKind,
      String latestEvidence,
      String lastSessionId,
      LocalDateTime lastAt
  ) {}

  private String buildExtractionInput(InterviewReportDTO report) {
    StringBuilder sb = new StringBuilder();
    sb.append("面试评估报告：\n");
    sb.append("总分：").append(report.overallScore()).append("\n");
    sb.append("总体评价：").append(nullSafe(report.overallFeedback())).append("\n");
    if (report.strengths() != null && !report.strengths().isEmpty()) {
      sb.append("优势：").append(String.join("；", report.strengths())).append("\n");
    }
    if (report.improvements() != null && !report.improvements().isEmpty()) {
      sb.append("改进建议：").append(String.join("；", report.improvements())).append("\n");
    }
    if (report.questionDetails() != null) {
      sb.append("逐题评估：\n");
      report.questionDetails().stream()
          .limit(MAX_REPORT_DETAIL_ITEMS)
          .forEach(q -> sb.append("- [").append(nullSafe(q.category())).append("] ")
              .append(nullSafe(q.question()))
              .append("（得分 ").append(q.score()).append("）：")
              .append(nullSafe(q.feedback())).append('\n'));
    }
    return sb.toString();
  }

  private boolean isValidKind(String kind) {
    if (kind == null) {
      return false;
    }
    String normalized = kind.strip().toLowerCase();
    return CandidateMemoryEntity.KIND_STRENGTH.equals(normalized)
        || CandidateMemoryEntity.KIND_WEAKNESS.equals(normalized);
  }

  private String truncate(String text) {
    if (text == null) {
      return "";
    }
    String normalized = text.strip();
    return normalized.length() <= MAX_EVIDENCE_LENGTH
        ? normalized : normalized.substring(0, MAX_EVIDENCE_LENGTH);
  }

  private String nullSafe(String text) {
    return text == null ? "" : text;
  }
}
