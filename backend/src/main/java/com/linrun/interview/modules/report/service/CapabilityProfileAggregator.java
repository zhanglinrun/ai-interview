package com.linrun.interview.modules.report.service;

import com.linrun.interview.modules.report.model.CapabilityState;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 最近三次有效证据的确定性投影。LLM 只能提供观察，不能直接修改画像状态。
 */
public class CapabilityProfileAggregator {

  private static final int RECENT_LIMIT = 3;
  private static final int CONFLICT_SPREAD = 35;

  public Projection project(List<Observation> observations) {
    List<Observation> recent = observations == null ? List.of() : observations.stream()
        .filter(Observation::valid)
        .sorted(Comparator.comparing(
            Observation::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(RECENT_LIMIT)
        .toList();
    if (recent.isEmpty()) {
      return new Projection(CapabilityState.UNVERIFIED, false, List.of());
    }

    List<Integer> scores = recent.stream().map(Observation::combinedScore).toList();
    int min = scores.stream().mapToInt(Integer::intValue).min().orElse(0);
    int max = scores.stream().mapToInt(Integer::intValue).max().orElse(0);
    if (scores.size() >= 2 && max - min >= CONFLICT_SPREAD) {
      return new Projection(CapabilityState.REVIEW, true, recent);
    }

    double average = scores.stream().mapToInt(Integer::intValue).average().orElse(0.0d);
    CapabilityState state;
    if (average < 60.0d) {
      state = CapabilityState.WEAK;
    } else if (recent.stream().filter(Observation::promotionEligible).count() < 2) {
      state = CapabilityState.UNVERIFIED;
    } else if (average >= 85.0d && recent.stream().allMatch(Observation::strongEvidence)) {
      state = CapabilityState.STRENGTH;
    } else if (average >= 70.0d) {
      state = CapabilityState.STABLE;
    } else {
      state = CapabilityState.WEAK;
    }
    return new Projection(state, false, recent);
  }

  public record Observation(
      String evidenceRecordId,
      Integer technicalScore,
      Integer completenessScore,
      Boolean objectivePassed,
      double confidence,
      boolean eligibleForPromotion,
      boolean hintUsed,
      boolean answerViewed,
      int redoCount,
      LocalDateTime occurredAt
  ) {
    boolean valid() {
      return evidenceRecordId != null && !evidenceRecordId.isBlank()
          && technicalScore != null && completenessScore != null
          && confidence >= 0.55d;
    }

    int combinedScore() {
      int technical = normalize(technicalScore);
      int completeness = normalize(completenessScore);
      int base = (int) Math.round(technical * 0.65d + completeness * 0.35d);
      if (Boolean.FALSE.equals(objectivePassed)) {
        return Math.min(base, 59);
      }
      return base;
    }

    boolean strongEvidence() {
      return promotionEligible()
          && !Boolean.FALSE.equals(objectivePassed);
    }

    boolean promotionEligible() {
      return eligibleForPromotion && !hintUsed && !answerViewed && redoCount == 0;
    }

    private int normalize(int value) {
      if (value >= 0 && value <= 5) {
        return Math.min(100, value * 20);
      }
      return Math.max(0, Math.min(100, value));
    }
  }

  public record Projection(
      CapabilityState state,
      boolean reviewRequired,
      List<Observation> recent
  ) {
    public Projection {
      recent = recent == null ? List.of() : List.copyOf(recent);
    }
  }
}
