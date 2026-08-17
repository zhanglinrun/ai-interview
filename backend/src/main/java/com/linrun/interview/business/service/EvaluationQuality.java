package com.linrun.interview.business.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Distinguishes a real zero (no answers) from a failed evaluation that used to
 * be persisted as {@code EVALUATED + overallScore=0}.
 */
public final class EvaluationQuality {

  public static final String UNSCORED_FEEDBACK = "该题未成功生成评估结果。";
  public static final String BATCH_FAILED_MESSAGE = "批次评估失败，未生成该批题目的评分。";
  public static final int MAX_COMPENSATION_ATTEMPTS = 3;

  private static final Pattern COMPENSATE = Pattern.compile("^\\[compensate:(\\d+)]");
  private static final List<String> DEGRADED_MARKERS = List.of(
      "按 0 分处理",
      "按 0 分兜底",
      "未成功生成评估结果",
      "批次评估失败",
      "未生成该批题目的评分",
      "8道题均按0分兜底",
      "均按0分兜底");

  private EvaluationQuality() {
  }

  public static boolean isDegradedFeedback(String feedback) {
    if (feedback == null || feedback.isBlank()) {
      return false;
    }
    return DEGRADED_MARKERS.stream().anyMatch(feedback::contains);
  }

  public static boolean isValidStoredReport(String overallFeedback, boolean anyAnswered,
                                            List<String> questionFeedbacks) {
    if (isDegradedFeedback(overallFeedback)) {
      return false;
    }
    if (!anyAnswered) {
      return true;
    }
    if (questionFeedbacks == null || questionFeedbacks.isEmpty()) {
      return !isDegradedFeedback(overallFeedback);
    }
    return questionFeedbacks.stream().anyMatch(feedback -> !isDegradedFeedback(feedback));
  }

  public static int compensationAttempts(String evaluateError) {
    if (evaluateError == null || evaluateError.isBlank()) {
      return 0;
    }
    Matcher matcher = COMPENSATE.matcher(evaluateError.strip());
    if (!matcher.find()) {
      return 0;
    }
    try {
      return Integer.parseInt(matcher.group(1));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public static String withCompensationAttempt(int attempt, String error) {
    String body = error == null ? "" : error.replaceFirst("^\\[compensate:\\d+]\\s*", "");
    return "[compensate:" + Math.max(1, attempt) + "] " + body;
  }

  public static boolean canCompensate(int attempts) {
    return attempts < MAX_COMPENSATION_ATTEMPTS;
  }
}
