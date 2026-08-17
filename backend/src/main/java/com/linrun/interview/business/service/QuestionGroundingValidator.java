package com.linrun.interview.business.service;

import com.linrun.interview.business.vo.AgentQuestionOutput;
import com.linrun.interview.business.vo.InterviewEvidence.Bundle;
import com.linrun.interview.business.vo.TurnDecision;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 出题侧轻量 grounded 校验：编造 evidence_id、简历未出现的书名号/专名项目 → 打回重出。
 */
public final class QuestionGroundingValidator {

  /** 书名号或直角引号中的专名，常被模型当「项目名」写进题面。 */
  private static final Pattern NAMED_SPAN = Pattern.compile("[《「]([^《」》]{2,40})[》」]");

  private QuestionGroundingValidator() {
  }

  public record GroundingVerdict(boolean grounded, String retryHint) {
    public static GroundingVerdict ok() {
      return new GroundingVerdict(true, "");
    }

    public static GroundingVerdict reject(String retryHint) {
      return new GroundingVerdict(false, retryHint == null ? "" : retryHint);
    }
  }

  public static GroundingVerdict validate(AgentQuestionOutput output, TurnDecision decision,
                                          String resumeText) {
    if (output == null) {
      return GroundingVerdict.ok();
    }
    Set<String> allowed = allowedEvidenceIds(decision == null ? null : decision.evidence());
    List<String> fabricated = new ArrayList<>();
    for (String id : output.evidenceIds()) {
      if (id == null || id.isBlank()) {
        continue;
      }
      if (!allowed.contains(id.strip())) {
        fabricated.add(id.strip());
      }
    }
    if (!fabricated.isEmpty()) {
      return GroundingVerdict.reject(
          "evidence_ids 含未提供的 ID " + fabricated
              + "，只能使用本轮证据列表中的 ID，未使用则返回空数组。");
    }

    if (resumeText == null || resumeText.isBlank()) {
      return GroundingVerdict.ok();
    }
    String haystack = resumeText.toLowerCase(Locale.ROOT);
    String probe = ((output.question() == null ? "" : output.question()) + "\n"
        + (output.rationale() == null ? "" : output.rationale()));
    List<String> missing = new ArrayList<>();
    Matcher matcher = NAMED_SPAN.matcher(probe);
    while (matcher.find()) {
      String name = matcher.group(1).strip();
      if (name.length() < 2) {
        continue;
      }
      if (!haystack.contains(name.toLowerCase(Locale.ROOT))) {
        missing.add(name);
      }
    }
    if (!missing.isEmpty()) {
      return GroundingVerdict.reject(
          "题面/理由中的专名 " + missing + " 未在候选人简历中出现；"
              + "请改为简历已写明的项目名，或去掉书名号专名改用通用场景提问。");
    }
    return GroundingVerdict.ok();
  }

  static Set<String> allowedEvidenceIds(Bundle evidence) {
    Set<String> allowed = new LinkedHashSet<>();
    if (evidence == null) {
      return allowed;
    }
    allowed.addAll(evidence.promptEvidenceIds());
    allowed.addAll(evidence.candidateIds());
    return allowed;
  }
}
