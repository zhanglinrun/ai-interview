package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.ai.service.PromptSecurityConstants;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceCandidate;
import com.linrun.interview.rag.model.EvidencePacket;
import com.linrun.interview.rag.model.EvidenceStatus;
import com.linrun.interview.infra.observability.LlmUsageContext;
import com.linrun.interview.github.client.GithubEvidenceReader;
import com.linrun.interview.github.client.GithubUntrustedEvidenceFormatter;
import com.linrun.interview.business.vo.AnswerAssessment;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.RecommendedAction;
import com.linrun.interview.rag.model.EvidenceSnapshotEntity;
import com.linrun.interview.rag.service.EvidenceSnapshotService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** BYOK 单题评价；失败时保留答案并显式进入待复核，不伪造 AI 评分。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmJobInterviewAssessmentService implements JobInterviewAssessmentPort {

  private static final int MAX_EVIDENCE_CHARS = 6000;
  private static final int MAX_CLARIFICATION_CHARS = 1200;
  private static final int MAX_ATTEMPTS = 2;
  private static final String ASSESSMENT_OPERATION = "JOB_INTERVIEW_ANSWER_ASSESSMENT";
  private static final String CLARIFICATION_OPERATION = "JOB_INTERVIEW_CLARIFICATION";

  private final LlmProviderRegistry llmProviderRegistry;
  private final EvidenceSnapshotService evidenceSnapshotService;
  private final GithubEvidenceReader githubEvidenceReader;
  private final GithubUntrustedEvidenceFormatter githubEvidenceFormatter;
  private final ObjectMapper objectMapper;

  @Override
  public AssessmentOutcome assess(
      Long userId,
      JobInterviewSessionEntity session,
      JobInterviewQuestionEntity question,
      String answer
  ) {
    long started = System.nanoTime();
    EvidenceContext evidence = loadEvidence(userId, session, question.getEvidenceSnapshotId());
    TokenCounter tokens = new TokenCounter();
    Exception lastError = null;
    try (var ignored = LlmUsageContext.open(
        userId, session.getSessionId(), null, ASSESSMENT_OPERATION)) {
      for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
          ChatModel model = llmProviderRegistry.getUserChatModel(userId);
          ChatResponse response = model.chat(ChatRequest.builder()
              .messages(
                  SystemMessage.from(assessmentSystemPrompt()),
                  UserMessage.from(assessmentUserPrompt(question, answer, evidence, attempt)))
              .build());
          tokens.add(response.tokenUsage());
          LlmAssessment parsed = objectMapper.readValue(
              extractJson(response.aiMessage().text()), LlmAssessment.class);
          AnswerAssessment assessment = normalize(parsed, evidence);
          String followUp = assessment.pendingReview()
              || assessment.recommendedAction() == RecommendedAction.SWITCH_TOPIC
              ? null : truncate(blankToNull(parsed.followUpQuestion()), 500);
          return new AssessmentOutcome(
              assessment, followUp, elapsedMs(started), tokens.inputTokens,
              tokens.outputTokens, attempt - 1, null);
        } catch (Exception e) {
          lastError = e;
          log.warn(
              "岗位实战单题评价失败，准备重试或降级: sessionId={}, questionId={}, attempt={}/{}",
              session.getSessionId(), question.getId(), attempt, MAX_ATTEMPTS, e);
        }
      }
    }
    String degraded = lastError == null
        ? "AI_EVALUATION_UNAVAILABLE" : "AI_EVALUATION_UNAVAILABLE";
    return new AssessmentOutcome(
        AnswerAssessment.pending(evidence.status(), degraded), null, elapsedMs(started),
        tokens.inputTokens, tokens.outputTokens, MAX_ATTEMPTS - 1, degraded);
  }

  @Override
  public ClarificationOutcome clarify(
      Long userId,
      JobInterviewSessionEntity session,
      JobInterviewQuestionEntity question,
      String candidateQuestion
  ) {
    long started = System.nanoTime();
    String request = candidateQuestion == null || candidateQuestion.isBlank()
        ? "请澄清这道题的输入、输出或回答范围。" : candidateQuestion.trim();
    try (var ignored = LlmUsageContext.open(
        userId, session.getSessionId(), null, CLARIFICATION_OPERATION)) {
      ChatResponse response = llmProviderRegistry.getUserChatModel(userId).chat(
          ChatRequest.builder()
              .messages(
                  SystemMessage.from("""
                      你是技术面试官。只澄清题意、输入输出、术语或回答边界，不能透露答案、思路、
                      复杂度结论、评分标准或隐藏测试。用中文简洁回答。
                      """ + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION),
                  UserMessage.from("""
                      <data-boundary>
                      原问题：%s
                      候选人请求：%s
                      </data-boundary>
                      """.formatted(question.getQuestionText(), request)))
              .build());
      TokenUsage usage = response.tokenUsage();
      String message = truncate(response.aiMessage().text(), MAX_CLARIFICATION_CHARS);
      if (message == null || message.isBlank()) {
        throw new IllegalStateException("澄清回复为空");
      }
      return new ClarificationOutcome(
          message, elapsedMs(started), input(usage), output(usage), 0, null);
    } catch (Exception e) {
      log.warn("岗位实战澄清调用失败，返回安全兜底: sessionId={}, questionId={}",
          session.getSessionId(), question.getId(), e);
      return new ClarificationOutcome(
          "请按题面给出的范围回答；若某个条件未明确，可以先陈述你的合理假设，再继续分析。",
          elapsedMs(started), null, null, 0, "AI_CLARIFICATION_UNAVAILABLE");
    }
  }

  private AnswerAssessment normalize(LlmAssessment raw, EvidenceContext evidence) {
    int technical = clamp(raw.technicalCorrectness());
    int completeness = clamp(raw.completeness());
    double confidence = Math.max(0.0d, Math.min(1.0d, raw.confidence()));
    RecommendedAction action = parseAction(raw.recommendedAction());
    boolean sourceUnavailable = !evidence.sourceAvailable();
    boolean pending = confidence < 0.55d || sourceUnavailable;
    String consistency;
    if (evidence.status() == EvidenceStatus.NONE) {
      consistency = "UNVERIFIED";
    } else if (sourceUnavailable) {
      consistency = "UNVERIFIABLE_AFTER_DELETION";
    } else {
      consistency = normalizeConsistency(raw.factualConsistency());
    }
    if (pending) {
      action = RecommendedAction.SWITCH_TOPIC;
    }
    return new AnswerAssessment(
        technical,
        completeness,
        consistency,
        evidence.status(),
        confidence,
        action,
        truncate(raw.rationale(), 2000),
        evidence.objectiveEvidenceIds(),
        pending);
  }

  private EvidenceContext loadEvidence(
      Long userId,
      JobInterviewSessionEntity session,
      String snapshotId
  ) {
    if (snapshotId == null || snapshotId.isBlank()) {
      return EvidenceContext.none();
    }
    try {
      EvidenceSnapshotEntity entity = evidenceSnapshotService.get(userId, snapshotId);
      if (entity == null) {
        return EvidenceContext.none();
      }
      EvidencePacket packet = objectMapper.readValue(entity.getPacketJson(), EvidencePacket.class);
      List<String> ids = packet.candidates().stream()
          .map(candidate -> candidate.ref().evidenceId()).distinct().toList();
      String snapshotText = evidenceText(packet.candidates(), MAX_EVIDENCE_CHARS / 2);
      String verifiedGithub = Boolean.TRUE.equals(entity.getSourceAvailable())
          ? loadVerifiedGithubEvidence(userId, session, packet.candidates()) : "";
      String combined = verifiedGithub.isBlank()
          ? snapshotText : truncate(verifiedGithub + "\n" + snapshotText, MAX_EVIDENCE_CHARS);
      return new EvidenceContext(
          entity.getEvidenceStatus(), Boolean.TRUE.equals(entity.getSourceAvailable()),
          combined, ids);
    } catch (Exception e) {
      log.warn("读取单题证据快照失败，按 NONE 评价: snapshotId={}", snapshotId, e);
      return EvidenceContext.none();
    }
  }

  private String loadVerifiedGithubEvidence(
      Long userId,
      JobInterviewSessionEntity session,
      List<EvidenceCandidate> candidates
  ) {
    if (session.getGithubRepositoryId() == null
        || session.getGithubCommitSha() == null
        || session.getGithubCommitSha().isBlank()) {
      return "";
    }
    List<EvidenceCandidate> githubCandidates = candidates.stream()
        .filter(item -> item.ref().dataDomain() == DataDomain.GITHUB)
        .toList();
    if (githubCandidates.isEmpty()) {
      return "";
    }
    StringBuilder verifiedText = new StringBuilder();
    int perEvidenceLimit = Math.max(800, MAX_EVIDENCE_CHARS / Math.max(2, githubCandidates.size()));
    for (EvidenceCandidate candidate : githubCandidates) {
      try {
        var verified = githubEvidenceReader.readEvidence(
            userId,
            session.getGithubRepositoryId(),
            session.getGithubCommitSha(),
            candidate.ref().evidenceId());
        if (verifiedText.length() > 0) {
          verifiedText.append('\n');
        }
        verifiedText.append(githubEvidenceFormatter.format(verified, perEvidenceLimit));
      } catch (Exception e) {
        log.warn(
            "GitHub 证据按需复核失败，继续使用冻结快照: sessionId={}, evidenceId={}, failureType={}",
            session.getSessionId(), candidate.ref().evidenceId(), e.getClass().getSimpleName());
      }
    }
    return truncate(verifiedText.toString(), MAX_EVIDENCE_CHARS / 2);
  }

  private String evidenceText(List<EvidenceCandidate> candidates, int maxChars) {
    StringBuilder result = new StringBuilder();
    for (EvidenceCandidate candidate : candidates) {
      if (result.length() >= maxChars) {
        break;
      }
      result.append('[').append(candidate.ref().evidenceId()).append("] ")
          .append(candidate.text()).append('\n');
    }
    return truncate(result.toString(), maxChars);
  }

  private String assessmentSystemPrompt() {
    return """
        你是严谨的技术面试评价器。技术正确性与候选人事实核验必须分开判断。
        仅输出 JSON 对象，不要 Markdown：
        {"technicalCorrectness":0,"completeness":0,"factualConsistency":"UNVERIFIED",
        "confidence":0.0,"recommendedAction":"SWITCH_TOPIC","rationale":"",
        "followUpQuestion":""}
        technicalCorrectness、completeness 为 0-100；recommendedAction 只能是
        DEEPEN、CLARIFY、REMEDIATE、SWITCH_TOPIC。只有证据直接支持时才能断言候选人项目事实；
        没有证据时仍可评价通用技术内容，但 factualConsistency 必须为 UNVERIFIED。
        追问必须承接候选人回答里真实出现的一个具体事实，例如表名、字段、类、组件、协议、指标、
        故障现象或技术选型；题面点出该事实，只继续问一个重点。若回答没有具体事实，先让候选人
        补一个实例。追问要像真实面试官直接提问，不能复述 JD，不能出现“目标岗位强调”“围绕某能力”
        “请讲清调用链、决策、失败路径和验证证据”等出题指令，也不能泄露参考答案。除非回答明确
        说明候选人亲历过某次故障或处置过程，否则不得要求其讲“你遇到过的真实故障”。必须改为
        条件式问法：“如果实际遇到过类似问题，可以结合案例说明；如果没有，请说明你会如何排查。”
        追问中的每个数据源、组件、动作、参数和因果都必须能从回答直接推出，不能把尚未说明的行动
        写成既成事实。回答只说“用离线坏例验证”时，可以让候选人选一个坏例说明验证方法，但不能
        假定其调整过检索权重、重排序策略或已经得到提升数据。回答没有给出量化对比时，不得追问
        “效果提升了多少”，应改问如何建立基线并验证效果。无法形成有事实锚点的追问时，
        followUpQuestion 返回空字符串并选择 SWITCH_TOPIC。
        """ + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
  }

  private String assessmentUserPrompt(
      JobInterviewQuestionEntity question,
      String answer,
      EvidenceContext evidence,
      int attempt
  ) {
    String repair = attempt == 1 ? "" : "上次输出无法解析，本次必须只返回合法 JSON。\n";
    return repair + """
        <data-boundary>
        问题：%s
        候选人回答：%s
        证据状态：%s
        可复核：%s
        客观证据：
        %s
        </data-boundary>
        """.formatted(
        question.getQuestionText(), answer, evidence.status(), evidence.sourceAvailable(),
        evidence.text().isBlank() ? "无" : evidence.text());
  }

  private String extractJson(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("模型返回为空");
    }
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("模型未返回 JSON 对象");
    }
    return raw.substring(start, end + 1);
  }

  private RecommendedAction parseAction(String raw) {
    try {
      return RecommendedAction.valueOf(raw == null
          ? "SWITCH_TOPIC" : raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return RecommendedAction.SWITCH_TOPIC;
    }
  }

  private String normalizeConsistency(String raw) {
    if (raw == null || raw.isBlank()) {
      return "UNVERIFIED";
    }
    String value = raw.trim().toUpperCase(Locale.ROOT);
    return switch (value) {
      case "CONSISTENT", "PARTIALLY_CONSISTENT", "CONFLICT", "UNVERIFIED" -> value;
      default -> "UNVERIFIED";
    };
  }

  private int clamp(Integer value) {
    return Math.max(0, Math.min(100, value == null ? 0 : value));
  }

  private long elapsedMs(long started) {
    return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
  }

  private Integer input(TokenUsage usage) {
    return usage == null ? null : usage.inputTokenCount();
  }

  private Integer output(TokenUsage usage) {
    return usage == null ? null : usage.outputTokenCount();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  private record LlmAssessment(
      Integer technicalCorrectness,
      Integer completeness,
      String factualConsistency,
      double confidence,
      String recommendedAction,
      String rationale,
      String followUpQuestion
  ) {
  }

  private record EvidenceContext(
      EvidenceStatus status,
      boolean sourceAvailable,
      String text,
      List<String> objectiveEvidenceIds
  ) {
    private EvidenceContext {
      status = status == null ? EvidenceStatus.NONE : status;
      text = text == null ? "" : text;
      objectiveEvidenceIds = objectiveEvidenceIds == null
          ? List.of() : List.copyOf(objectiveEvidenceIds);
    }

    private static EvidenceContext none() {
      return new EvidenceContext(EvidenceStatus.NONE, true, "", List.of());
    }
  }

  private static final class TokenCounter {
    private Integer inputTokens;
    private Integer outputTokens;

    private void add(TokenUsage usage) {
      if (usage == null) {
        return;
      }
      inputTokens = sum(inputTokens, usage.inputTokenCount());
      outputTokens = sum(outputTokens, usage.outputTokenCount());
    }

    private Integer sum(Integer left, Integer right) {
      if (left == null) {
        return right;
      }
      return right == null ? left : left + right;
    }
  }
}
