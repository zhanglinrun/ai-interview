package com.linrun.interview.modules.knowledgebase.rag;

/**
 * 意图识别结果（亮点4，面试领域通用二分类版）。
 *
 * @param related 问题是否与面试准备 / 技术知识 / 编程 / 简历 / 职业规划 / 求职等相关
 * @param reason  判定理由（不相关时给出，便于排查；可为 null）
 */
public record IntentRecognitionResult(boolean related, String reason) {
}
