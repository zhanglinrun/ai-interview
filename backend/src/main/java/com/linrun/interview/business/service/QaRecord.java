package com.linrun.interview.business.service;

/**
 * 通用面试问答记录
 */
public record QaRecord(
    int questionIndex,
    String question,
    String category,
    String userAnswer,
    Boolean criticApproved
) {
  public QaRecord(int questionIndex, String question, String category, String userAnswer) {
    this(questionIndex, question, category, userAnswer, null);
  }
}
