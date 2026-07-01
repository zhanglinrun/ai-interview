package com.linrun.interview.modules.interview.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 面试答案实体
 */
@TableName("interview_answers")
public class InterviewAnswerEntity {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long userId;

  private Long sessionId;

  @TableField(exist = false)
  private InterviewSessionEntity session;

  private Integer questionIndex;

  private String question;

  private String category;

  private String userAnswer;

  private Integer score;

  private String feedback;

  private String referenceAnswer;

  private String keyPointsJson;

  private LocalDateTime answeredAt;

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

  public Long getSessionId() {
    return sessionId;
  }

  public void setSessionId(Long sessionId) {
    this.sessionId = sessionId;
  }

  public InterviewSessionEntity getSession() {
    return session;
  }

  public void setSession(InterviewSessionEntity session) {
    this.session = session;
    this.sessionId = session != null ? session.getId() : null;
  }

  public Integer getQuestionIndex() {
    return questionIndex;
  }

  public void setQuestionIndex(Integer questionIndex) {
    this.questionIndex = questionIndex;
  }

  public String getQuestion() {
    return question;
  }

  public void setQuestion(String question) {
    this.question = question;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getUserAnswer() {
    return userAnswer;
  }

  public void setUserAnswer(String userAnswer) {
    this.userAnswer = userAnswer;
  }

  public Integer getScore() {
    return score;
  }

  public void setScore(Integer score) {
    this.score = score;
  }

  public String getFeedback() {
    return feedback;
  }

  public void setFeedback(String feedback) {
    this.feedback = feedback;
  }

  public String getReferenceAnswer() {
    return referenceAnswer;
  }

  public void setReferenceAnswer(String referenceAnswer) {
    this.referenceAnswer = referenceAnswer;
  }

  public String getKeyPointsJson() {
    return keyPointsJson;
  }

  public void setKeyPointsJson(String keyPointsJson) {
    this.keyPointsJson = keyPointsJson;
  }

  public LocalDateTime getAnsweredAt() {
    return answeredAt;
  }

  public void setAnsweredAt(LocalDateTime answeredAt) {
    this.answeredAt = answeredAt;
  }
}
