package com.linrun.interview.business.service;

/**
 * 通用面试问答记录
 */
public record QaRecord(
    int questionIndex,
    String question,
    String category,
    String userAnswer   // null 表示未回答
) {}
