package com.linrun.interview.modules.report.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.evidence.EvidenceStatus;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.capability.mapper.CapabilityAtomDefinitionMapper;
import com.linrun.interview.modules.capability.model.CapabilityAtomDefinitionEntity;
import com.linrun.interview.modules.report.dto.ReportContracts.CapabilityGap;
import com.linrun.interview.modules.report.dto.TrainingContracts.CompleteTrainingRequest;
import com.linrun.interview.modules.report.dto.TrainingContracts.CreateTrainingRequest;
import com.linrun.interview.modules.report.dto.TrainingContracts.TrainingInteractionRequest;
import com.linrun.interview.modules.report.dto.TrainingContracts.TrainingTaskView;
import com.linrun.interview.modules.report.mapper.TrainingTaskMapper;
import com.linrun.interview.modules.report.model.CapabilityEvidenceEntity;
import com.linrun.interview.modules.report.model.CapabilityEvidenceSource;
import com.linrun.interview.modules.report.model.TrainingStatus;
import com.linrun.interview.modules.report.model.TrainingTaskEntity;
import com.linrun.interview.modules.report.model.TrainingType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TrainingService {

  private static final Set<String> ALLOWED_SCOPES = Set.of(
      "PLATFORM", "JOB", "CANDIDATE", "GITHUB");

  private final TrainingTaskMapper taskMapper;
  private final CapabilityAtomDefinitionMapper atomMapper;
  private final CapabilityProfileService profileService;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  public TrainingTaskView createManual(Long userId, CreateTrainingRequest request) {
    String atomId = request.capabilityAtomId().strip();
    List<String> scopes = normalizeScopes(request.evidenceScopes());
    String question = request.question() == null || request.question().isBlank()
        ? defaultQuestion(atomId, request.trainingType()) : request.question().strip();
    TrainingTaskEntity task = TrainingTaskEntity.builder()
        .taskId(UUID.randomUUID().toString())
        .userId(userId)
        .capabilityAtomId(atomId)
        .trainingType(request.trainingType())
        .status(TrainingStatus.RECOMMENDED)
        .questionText(question)
        .questionVersion("manual-v1")
        .evidenceScopeJson(writeJson(scopes))
        .hintUsed(false)
        .answerViewed(false)
        .redoCount(0)
        .createdAt(LocalDateTime.now())
        .updatedAt(LocalDateTime.now())
        .build();
    taskMapper.insert(task);
    return toView(task);
  }

  @Transactional(rollbackFor = Exception.class)
  public List<CapabilityGap> ensureRecommended(
      Long userId,
      String reportId,
      List<CapabilityGap> gaps
  ) {
    if (gaps == null || gaps.isEmpty()) {
      return List.of();
    }
    List<CapabilityGap> result = new ArrayList<>();
    for (CapabilityGap gap : gaps.stream().limit(3).toList()) {
      TrainingTaskEntity task = taskMapper.selectOne(
          Wrappers.<TrainingTaskEntity>lambdaQuery()
              .eq(TrainingTaskEntity::getUserId, userId)
              .eq(TrainingTaskEntity::getReportId, reportId)
              .eq(TrainingTaskEntity::getCapabilityAtomId, gap.capabilityAtomId()));
      if (task == null) {
        task = TrainingTaskEntity.builder()
            .taskId(UUID.randomUUID().toString())
            .userId(userId)
            .reportId(reportId)
            .capabilityAtomId(gap.capabilityAtomId())
            .trainingType(gap.trainingType())
            .status(TrainingStatus.RECOMMENDED)
            .sourceQuestionId(gap.sourceQuestionId())
            .questionText(defaultQuestion(gap.capabilityAtomId(), gap.trainingType()))
            .questionVersion("report-v1")
            .evidenceScopeJson(writeJson(List.of("PLATFORM", "JOB")))
            .hintUsed(false)
            .answerViewed(false)
            .redoCount(0)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
        taskMapper.insert(task);
      }
      result.add(new CapabilityGap(
          gap.capabilityAtomId(), gap.capabilityName(), gap.reason(),
          gap.sourceQuestionId(), gap.evidenceRecordIds(), gap.trainingType(), task.getTaskId()));
    }
    return result;
  }

  public List<TrainingTaskView> list(Long userId, TrainingStatus status) {
    return taskMapper.selectList(Wrappers.<TrainingTaskEntity>lambdaQuery()
            .eq(TrainingTaskEntity::getUserId, userId)
            .eq(status != null, TrainingTaskEntity::getStatus, status)
            .orderByDesc(TrainingTaskEntity::getUpdatedAt)
            .last("LIMIT 200"))
        .stream()
        .map(this::toView)
        .toList();
  }

  @Transactional(rollbackFor = Exception.class)
  public TrainingTaskView recordInteraction(
      Long userId,
      String taskId,
      TrainingInteractionRequest request
  ) {
    TrainingTaskEntity task = require(userId, taskId);
    if (task.getStatus() == TrainingStatus.COMPLETED) {
      return toView(task);
    }
    task.setStatus(TrainingStatus.IN_PROGRESS);
    task.setStartedAt(task.getStartedAt() == null ? LocalDateTime.now() : task.getStartedAt());
    task.setHintUsed(Boolean.TRUE.equals(task.getHintUsed()) || request.hintUsed());
    task.setAnswerViewed(Boolean.TRUE.equals(task.getAnswerViewed()) || request.answerViewed());
    if (request.redo()) {
      task.setRedoCount(value(task.getRedoCount()) + 1);
    }
    task.setUpdatedAt(LocalDateTime.now());
    taskMapper.updateById(task);
    return toView(task);
  }

  @Transactional(rollbackFor = Exception.class)
  public TrainingTaskView complete(
      Long userId,
      String taskId,
      CompleteTrainingRequest request
  ) {
    TrainingTaskEntity task = require(userId, taskId);
    if (task.getStatus() == TrainingStatus.COMPLETED) {
      return toView(task);
    }
    boolean hintUsed = Boolean.TRUE.equals(task.getHintUsed()) || request.hintUsed();
    boolean answerViewed = Boolean.TRUE.equals(task.getAnswerViewed()) || request.answerViewed();
    int redoCount = Math.max(value(task.getRedoCount()), request.redoCount());
    boolean eligible = !hintUsed && !answerViewed && redoCount == 0;
    LocalDateTime now = LocalDateTime.now();

    task.setStatus(TrainingStatus.COMPLETED);
    task.setHintUsed(hintUsed);
    task.setAnswerViewed(answerViewed);
    task.setRedoCount(redoCount);
    task.setResultScore(request.score());
    task.setStartedAt(task.getStartedAt() == null ? now : task.getStartedAt());
    task.setCompletedAt(now);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    CapabilityEvidenceEntity evidence = CapabilityEvidenceEntity.builder()
        .evidenceRecordId(UUID.randomUUID().toString())
        .userId(userId)
        .trainingTaskId(taskId)
        .capabilityAtomId(task.getCapabilityAtomId())
        .sourceType(CapabilityEvidenceSource.TRAINING)
        .difficulty("TRAINING")
        .technicalScore(request.score())
        .completenessScore(request.score())
        .objectivePassed(request.objectivePassed())
        .confidence(BigDecimal.valueOf(eligible ? 0.70d : 0.40d))
        .evidenceStatus(EvidenceStatus.NONE)
        .evidenceRefsJson("[]")
        .observation(truncate(request.observation(), 500))
        .eligibleForPromotion(eligible)
        .hintUsed(hintUsed)
        .answerViewed(answerViewed)
        .redoCount(redoCount)
        .occurredAt(now)
        .createdAt(now)
        .build();
    profileService.insertAndRefresh(List.of(evidence));
    return toView(task);
  }

  private TrainingTaskEntity require(Long userId, String taskId) {
    TrainingTaskEntity task = taskMapper.selectOne(
        Wrappers.<TrainingTaskEntity>lambdaQuery()
            .eq(TrainingTaskEntity::getUserId, userId)
            .eq(TrainingTaskEntity::getTaskId, taskId));
    if (task == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "专项训练任务不存在");
    }
    return task;
  }

  private List<String> normalizeScopes(List<String> values) {
    LinkedHashSet<String> scopes = new LinkedHashSet<>();
    List<String> source = values == null || values.isEmpty() ? List.of("PLATFORM") : values;
    for (String value : source) {
      String scope = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
      if (!ALLOWED_SCOPES.contains(scope)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的证据域: " + value);
      }
      scopes.add(scope);
    }
    return List.copyOf(scopes);
  }

  private String defaultQuestion(String atomId, TrainingType type) {
    String name = atomName(atomId);
    return switch (type) {
      case ALGORITHM -> "围绕“" + name + "”完成一道算法题：先澄清边界，再说明复杂度并编码验证。";
      case PROJECT_DEEP_DIVE -> "围绕“" + name
          + "”选择一个真实项目实现，讲清调用链、关键取舍、失效路径和验证证据。";
      case ENGINEERING_SCENARIO -> "针对“" + name
          + "”设计一个工程故障场景，说明定位证据、恢复步骤、降级策略与防复发措施。";
      case TECHNICAL_FOUNDATION -> "闭卷解释“" + name
          + "”的核心原理、适用边界、常见误区，并结合本项目给出一个例子。";
    };
  }

  private String atomName(String atomId) {
    CapabilityAtomDefinitionEntity atom = atomMapper.selectOne(
        Wrappers.<CapabilityAtomDefinitionEntity>lambdaQuery()
            .eq(CapabilityAtomDefinitionEntity::getAtomId, atomId)
            .orderByDesc(CapabilityAtomDefinitionEntity::getCreatedAt)
            .last("LIMIT 1"));
    return atom == null || atom.getName() == null ? atomId : atom.getName();
  }

  private TrainingTaskView toView(TrainingTaskEntity task) {
    return new TrainingTaskView(
        task.getTaskId(), task.getReportId(), task.getCapabilityAtomId(),
        task.getTrainingType(), task.getStatus(), task.getSourceQuestionId(),
        task.getQuestionText(), task.getQuestionVersion(), readScopes(task.getEvidenceScopeJson()),
        Boolean.TRUE.equals(task.getHintUsed()), Boolean.TRUE.equals(task.getAnswerViewed()),
        value(task.getRedoCount()), task.getResultScore(), task.getCreatedAt(), task.getCompletedAt());
  }

  private String writeJson(List<String> values) {
    try {
      return objectMapper.writeValueAsString(values);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "训练证据域序列化失败", e);
    }
  }

  private List<String> readScopes(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {
      });
    } catch (Exception e) {
      return List.of();
    }
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.strip();
    return normalized.length() <= maxLength
        ? normalized : normalized.substring(0, maxLength);
  }
}
