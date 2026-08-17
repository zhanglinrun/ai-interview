package com.linrun.interview.business.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试三层记忆的只读投影：短期原文、本场压缩摘要、跨场长期观测。
 */
public record InterviewMemoryView(
    ShortTermMemoryView shortTerm,
    CompressedMemoryView compressed,
    List<LongTermMemoryItem> longTerm
) {
  public InterviewMemoryView {
    shortTerm = shortTerm == null ? ShortTermMemoryView.empty() : shortTerm;
    compressed = compressed == null ? CompressedMemoryView.empty() : compressed;
    longTerm = longTerm == null ? List.of() : List.copyOf(longTerm);
  }

  public record ShortTermMemoryView(
      String sessionId,
      String skillId,
      boolean live,
      int windowSize,
      int agentMessageCount,
      List<ShortTermTurn> turns
  ) {
    public ShortTermMemoryView {
      turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public static ShortTermMemoryView empty() {
      return new ShortTermMemoryView(null, null, false, 0, 0, List.of());
    }
  }

  public record ShortTermTurn(String role, String text) {
    public ShortTermTurn {
      role = role == null || role.isBlank() ? "OTHER" : role.strip();
      text = text == null ? "" : text;
    }
  }

  public record CompressedMemoryView(
      String sessionId,
      String skillId,
      List<CompressedTurn> turns
  ) {
    public CompressedMemoryView {
      turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public static CompressedMemoryView empty() {
      return new CompressedMemoryView(null, null, List.of());
    }
  }

  public record CompressedTurn(
      int questionIndex,
      String topic,
      String followUpAction,
      int meaningfulChars,
      boolean hasReasoning,
      boolean hasExample,
      boolean hasTradeOff,
      boolean expressesUncertainty
  ) {
    public CompressedTurn {
      topic = topic == null || topic.isBlank() ? "未命名主题" : topic.strip();
    }
  }

  public record LongTermMemoryItem(
      String topic,
      String capabilityAtomId,
      String masteryLevel,
      String verificationState,
      Integer averageScore,
      int observationCount,
      int sessionCount,
      String latestEvidence,
      LocalDateTime lastAt
  ) {
    public LongTermMemoryItem {
      topic = topic == null || topic.isBlank() ? "未命名主题" : topic.strip();
    }
  }
}
