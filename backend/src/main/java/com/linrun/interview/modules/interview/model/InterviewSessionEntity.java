package com.linrun.interview.modules.interview.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import com.linrun.interview.common.model.AsyncTaskStatus;
import com.linrun.interview.modules.resume.model.ResumeEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 面试会话实体
 */
@TableName("interview_sessions")
public class InterviewSessionEntity {
    
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    
    // 会话ID (UUID)
    private String sessionId;
    
    // 面试主题
    private String skillId = "java-backend";

    // 难度级别 (junior / mid / senior)
    private String difficulty = "mid";

    // 简历ID（直接映射FK列，避免LAZY加载触发额外查询）
    private Long resumeId;

    // 关联的简历（可选，支持无简历通用面试）
    @TableField(exist = false)
    private ResumeEntity resume;
    
    // 问题总数
    private Integer totalQuestions;
    
    // 当前问题索引
    private Integer currentQuestionIndex = 0;
    
    // 会话状态
    private SessionStatus status = SessionStatus.CREATED;
    
    // 问题列表 (JSON格式)
    private String questionsJson;
    
    // 总分 (0-100)
    private Integer overallScore;
    
    // 总体评价
    private String overallFeedback;
    
    // 优势 (JSON)
    private String strengthsJson;
    
    // 改进建议 (JSON)
    private String improvementsJson;
    
    // 参考答案 (JSON)
    private String referenceAnswersJson;
    
    // 面试答案记录
    @TableField(exist = false)
    private List<InterviewAnswerEntity> answers = new ArrayList<>();
    
    // 创建时间
    private LocalDateTime createdAt;
    
    // 完成时间
    private LocalDateTime completedAt;

    // 评估状态（异步评估）
    private AsyncTaskStatus evaluateStatus;

    // 评估错误信息
    private String evaluateError;

    // LLM提供商
    private String llmProvider = "dashscope";

    // 关联知识库 ID 列表（JSON）
    private String knowledgeBaseIdsJson;

    // Multi-Agent 编排的面试大纲（JSON，null 表示旧批量出题会话）
    private String interviewPlanJson;

    // 岗位实战增量字段；旧模拟面试为 null。
    private String preparationRunId;

    private Long jobDescriptionId;

    private String capabilityTemplateCode;

    private String currentStage;

    private Long sessionVersion;

    public enum SessionStatus {
        CREATED,      // 会话已创建
        READY,        // 岗位实战已准备
        IN_PROGRESS,  // 面试进行中
        PAUSED,       // 岗位实战断点续面
        COMPLETING,   // 正在收尾
        COMPLETED,    // 面试已完成
        EVALUATED,    // 旧模拟面试已生成评估报告
        ABORTED,      // 岗位实战已中止，不更新画像
        FAILED        // 岗位实战运行失败
    }
    
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    
    public Long getResumeId() {
        return resumeId;
    }

    public ResumeEntity getResume() {
        return resume;
    }

    public void setResume(ResumeEntity resume) {
        this.resume = resume;
        this.resumeId = resume != null ? resume.getId() : null;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }
    
    public Integer getTotalQuestions() {
        return totalQuestions;
    }
    
    public void setTotalQuestions(Integer totalQuestions) {
        this.totalQuestions = totalQuestions;
    }
    
    public Integer getCurrentQuestionIndex() {
        return currentQuestionIndex;
    }
    
    public void setCurrentQuestionIndex(Integer currentQuestionIndex) {
        this.currentQuestionIndex = currentQuestionIndex;
    }
    
    public SessionStatus getStatus() {
        return status;
    }
    
    public void setStatus(SessionStatus status) {
        this.status = status;
    }
    
    public String getQuestionsJson() {
        return questionsJson;
    }
    
    public void setQuestionsJson(String questionsJson) {
        this.questionsJson = questionsJson;
    }
    
    public Integer getOverallScore() {
        return overallScore;
    }
    
    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }
    
    public String getOverallFeedback() {
        return overallFeedback;
    }
    
    public void setOverallFeedback(String overallFeedback) {
        this.overallFeedback = overallFeedback;
    }
    
    public String getStrengthsJson() {
        return strengthsJson;
    }
    
    public void setStrengthsJson(String strengthsJson) {
        this.strengthsJson = strengthsJson;
    }
    
    public String getImprovementsJson() {
        return improvementsJson;
    }
    
    public void setImprovementsJson(String improvementsJson) {
        this.improvementsJson = improvementsJson;
    }
    
    public String getReferenceAnswersJson() {
        return referenceAnswersJson;
    }
    
    public void setReferenceAnswersJson(String referenceAnswersJson) {
        this.referenceAnswersJson = referenceAnswersJson;
    }
    
    public List<InterviewAnswerEntity> getAnswers() {
        return answers;
    }
    
    public void setAnswers(List<InterviewAnswerEntity> answers) {
        this.answers = answers;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
    
    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public AsyncTaskStatus getEvaluateStatus() {
        return evaluateStatus;
    }

    public void setEvaluateStatus(AsyncTaskStatus evaluateStatus) {
        this.evaluateStatus = evaluateStatus;
    }

    public String getEvaluateError() {
        return evaluateError;
    }

    public void setEvaluateError(String evaluateError) {
        this.evaluateError = evaluateError;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String getKnowledgeBaseIdsJson() {
        return knowledgeBaseIdsJson;
    }

    public void setKnowledgeBaseIdsJson(String knowledgeBaseIdsJson) {
        this.knowledgeBaseIdsJson = knowledgeBaseIdsJson;
    }

    public String getInterviewPlanJson() {
        return interviewPlanJson;
    }

    public void setInterviewPlanJson(String interviewPlanJson) {
        this.interviewPlanJson = interviewPlanJson;
    }

    public String getPreparationRunId() {
        return preparationRunId;
    }

    public void setPreparationRunId(String preparationRunId) {
        this.preparationRunId = preparationRunId;
    }

    public Long getJobDescriptionId() {
        return jobDescriptionId;
    }

    public void setJobDescriptionId(Long jobDescriptionId) {
        this.jobDescriptionId = jobDescriptionId;
    }

    public String getCapabilityTemplateCode() {
        return capabilityTemplateCode;
    }

    public void setCapabilityTemplateCode(String capabilityTemplateCode) {
        this.capabilityTemplateCode = capabilityTemplateCode;
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(String currentStage) {
        this.currentStage = currentStage;
    }

    public Long getSessionVersion() {
        return sessionVersion;
    }

    public void setSessionVersion(Long sessionVersion) {
        this.sessionVersion = sessionVersion;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public void addAnswer(InterviewAnswerEntity answer) {
        answers.add(answer);
        answer.setSessionId(this.id);
    }
}
