package com.linrun.interview.modules.resume.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;


import java.time.LocalDateTime;

/**
 * 简历评测结果实体
 * Resume Analysis Entity
 */
@TableName("resume_analyses")
public class ResumeAnalysisEntity {
    
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    
    // 关联的简历
    private Long resumeId;

    @TableField(exist = false)
    private ResumeEntity resume;
    
    // 总分 (0-100)
    private Integer overallScore;
    
    // 各维度评分
    private Integer contentScore;      // 内容完整性 (0-15)
    private Integer structureScore;    // 结构清晰度 (0-15)
    private Integer skillMatchScore;   // 技能匹配度 (0-20)
    private Integer expressionScore;   // 表达专业性 (0-10)
    private Integer projectScore;      // 项目经验 (0-40)
    
    // 简历摘要
    private String summary;
    
    // 优点列表 (JSON格式)
    private String strengthsJson;
    
    // 改进建议列表 (JSON格式)
    private String suggestionsJson;
    
    // 评测时间
    private LocalDateTime analyzedAt;
    
    
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
    
    public ResumeEntity getResume() {
        return resume;
    }
    
    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public void setResume(ResumeEntity resume) {
        this.resumeId = resume != null ? resume.getId() : null;
    }
    
    public Integer getOverallScore() {
        return overallScore;
    }
    
    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }
    
    public Integer getContentScore() {
        return contentScore;
    }
    
    public void setContentScore(Integer contentScore) {
        this.contentScore = contentScore;
    }
    
    public Integer getStructureScore() {
        return structureScore;
    }
    
    public void setStructureScore(Integer structureScore) {
        this.structureScore = structureScore;
    }
    
    public Integer getSkillMatchScore() {
        return skillMatchScore;
    }
    
    public void setSkillMatchScore(Integer skillMatchScore) {
        this.skillMatchScore = skillMatchScore;
    }
    
    public Integer getExpressionScore() {
        return expressionScore;
    }
    
    public void setExpressionScore(Integer expressionScore) {
        this.expressionScore = expressionScore;
    }
    
    public Integer getProjectScore() {
        return projectScore;
    }
    
    public void setProjectScore(Integer projectScore) {
        this.projectScore = projectScore;
    }
    
    public String getSummary() {
        return summary;
    }
    
    public void setSummary(String summary) {
        this.summary = summary;
    }
    
    public String getStrengthsJson() {
        return strengthsJson;
    }
    
    public void setStrengthsJson(String strengthsJson) {
        this.strengthsJson = strengthsJson;
    }
    
    public String getSuggestionsJson() {
        return suggestionsJson;
    }
    
    public void setSuggestionsJson(String suggestionsJson) {
        this.suggestionsJson = suggestionsJson;
    }
    
    public LocalDateTime getAnalyzedAt() {
        return analyzedAt;
    }
    
    public void setAnalyzedAt(LocalDateTime analyzedAt) {
        this.analyzedAt = analyzedAt;
    }
}
