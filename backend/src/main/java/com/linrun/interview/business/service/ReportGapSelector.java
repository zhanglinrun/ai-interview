package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.business.mapper.CapabilityAtomDefinitionMapper;
import com.linrun.interview.business.entity.CapabilityAtomDefinitionEntity;
import com.linrun.interview.business.mapper.JobInterviewQuestionMapper;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import com.linrun.interview.business.constant.JobInterviewStage;
import com.linrun.interview.business.vo.ReportContracts.CapabilityGap;
import com.linrun.interview.business.entity.CapabilityEvidenceEntity;
import com.linrun.interview.business.constant.TrainingType;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportGapSelector {

  private final CapabilityAtomDefinitionMapper atomMapper;
  private final JobInterviewQuestionMapper questionMapper;

  public List<CapabilityGap> select(List<CapabilityEvidenceEntity> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return List.of();
    }
    Map<Long, JobInterviewQuestionEntity> questions = questionMap(evidence);
    Map<String, String> names = nameMap();
    Map<String, ScoredEvidence> weakestByAtom = new LinkedHashMap<>();
    evidence.stream()
        .filter(this::valid)
        .map(item -> new ScoredEvidence(item, score(item)))
        .sorted(Comparator.comparingInt(ScoredEvidence::score)
            .thenComparing(item -> item.evidence().getOccurredAt(),
                Comparator.nullsLast(Comparator.reverseOrder())))
        .forEach(scored -> weakestByAtom.putIfAbsent(
            scored.evidence().getCapabilityAtomId(), scored));

    return weakestByAtom.values().stream()
        .filter(item -> item.score() < 70)
        .limit(3)
        .map(item -> toGap(item, questions, names))
        .toList();
  }

  private CapabilityGap toGap(
      ScoredEvidence scored,
      Map<Long, JobInterviewQuestionEntity> questions,
      Map<String, String> names
  ) {
    CapabilityEvidenceEntity evidence = scored.evidence();
    JobInterviewQuestionEntity question = questions.get(evidence.getQuestionId());
    TrainingType type = trainingType(question == null ? null : question.getStage());
    String reason;
    if (Boolean.FALSE.equals(evidence.getObjectivePassed())) {
      reason = "客观执行未通过；建议重新澄清边界、验证复杂度并补充反例。";
    } else if (scored.score() < 60) {
      reason = "本场结构化评价显示技术正确性或完整性存在明显缺口，建议优先闭卷复测。";
    } else {
      reason = "主链路已部分覆盖，但边界、取舍或故障路径仍需补强。";
    }
    return new CapabilityGap(
        evidence.getCapabilityAtomId(),
        names.getOrDefault(evidence.getCapabilityAtomId(), evidence.getCapabilityAtomId()),
        reason, evidence.getQuestionId(), List.of(evidence.getEvidenceRecordId()), type, null);
  }

  private boolean valid(CapabilityEvidenceEntity item) {
    return item != null && item.getCapabilityAtomId() != null
        && item.getTechnicalScore() != null && item.getCompletenessScore() != null
        && item.getConfidence() != null && item.getConfidence().doubleValue() >= 0.55d;
  }

  private int score(CapabilityEvidenceEntity item) {
    int technical = normalize(item.getTechnicalScore());
    int completeness = normalize(item.getCompletenessScore());
    int combined = (int) Math.round(technical * 0.65d + completeness * 0.35d);
    return Boolean.FALSE.equals(item.getObjectivePassed()) ? Math.min(59, combined) : combined;
  }

  private int normalize(int score) {
    if (score >= 0 && score <= 5) {
      return Math.min(100, score * 20);
    }
    return Math.max(0, Math.min(100, score));
  }

  private TrainingType trainingType(JobInterviewStage stage) {
    if (stage == null) {
      return TrainingType.TECHNICAL_FOUNDATION;
    }
    return switch (stage) {
      case ALGORITHM -> TrainingType.ALGORITHM;
      case PROJECT_DEEP_DIVE -> TrainingType.PROJECT_DEEP_DIVE;
      case ENGINEERING_SCENARIO -> TrainingType.ENGINEERING_SCENARIO;
      case POSITION_TECH -> TrainingType.TECHNICAL_FOUNDATION;
    };
  }

  private Map<Long, JobInterviewQuestionEntity> questionMap(
      List<CapabilityEvidenceEntity> evidence
  ) {
    List<Long> ids = evidence.stream()
        .map(CapabilityEvidenceEntity::getQuestionId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, JobInterviewQuestionEntity> result = new LinkedHashMap<>();
    questionMapper.selectBatchIds(ids).forEach(question -> result.put(question.getId(), question));
    return result;
  }

  private Map<String, String> nameMap() {
    Map<String, String> result = new LinkedHashMap<>();
    atomMapper.selectList(Wrappers.<CapabilityAtomDefinitionEntity>lambdaQuery()
            .orderByDesc(CapabilityAtomDefinitionEntity::getCreatedAt))
        .forEach(atom -> result.putIfAbsent(atom.getAtomId(), atom.getName()));
    return result;
  }

  private record ScoredEvidence(CapabilityEvidenceEntity evidence, int score) {
  }
}
