package com.linrun.interview.business.vo;

import com.linrun.interview.business.service.InterviewTopic.Category;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建面试会话请求
 */
public record CreateInterviewRequest(
    String resumeText,      // 简历文本内容（可选，无简历时为通用面试）

    @Min(value = 3, message = "题目数量最少3题")
    @Max(value = 20, message = "题目数量最多20题")
    int questionCount,      // 面试题目数量 (3-20)

    Long resumeId,          // 简历ID（可选，无简历时不传）

    Boolean forceCreate,    // 是否强制创建新会话（忽略未完成的会话），默认为 false

    String llmProvider,     // LLM提供商

    @NotBlank(message = "面试主题不能为空")
    String skillId,         // 历史兼容字段：面试主题 ID（如 java-backend、ai-rag-agent、custom）

    String difficulty,      // 难度级别: junior / mid / senior

    List<Category> customCategories,      // 自定义面试冻结的能力分类

    String jdText,                         // JD 原文（可选；挂到主题上供 Planner / 出题使用）

    List<Long> knowledgeBaseIds            // 关联的岗位知识库 ID（可选，出题时 RAG 检索注入）
) {}
