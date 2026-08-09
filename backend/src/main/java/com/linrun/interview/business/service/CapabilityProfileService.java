package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.mapper.CapabilityAtomDefinitionMapper;
import com.linrun.interview.business.entity.CapabilityAtomDefinitionEntity;
import com.linrun.interview.business.vo.ReportContracts.CapabilityProfileView;
import com.linrun.interview.business.mapper.CapabilityEvidenceMapper;
import com.linrun.interview.business.mapper.CapabilityProfileMapper;
import com.linrun.interview.business.entity.CapabilityEvidenceEntity;
import com.linrun.interview.business.entity.CapabilityProfileEntity;
import com.linrun.interview.business.service.CapabilityProfileAggregator.Observation;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CapabilityProfileService {

  private final CapabilityEvidenceMapper evidenceMapper;
  private final CapabilityProfileMapper profileMapper;
  private final CapabilityAtomDefinitionMapper atomMapper;
  private final ObjectMapper objectMapper;
  private final CapabilityProfileAggregator aggregator = new CapabilityProfileAggregator();

  @Transactional(rollbackFor = Exception.class)
  public void insertAndRefresh(List<CapabilityEvidenceEntity> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return;
    }
    Map<String, Long> atoms = new LinkedHashMap<>();
    for (CapabilityEvidenceEntity item : evidence) {
      if (item == null || item.getUserId() == null || item.getCapabilityAtomId() == null
          || item.getCapabilityAtomId().isBlank()) {
        continue;
      }
      boolean exists = evidenceMapper.selectCount(
          Wrappers.<CapabilityEvidenceEntity>lambdaQuery()
              .eq(CapabilityEvidenceEntity::getEvidenceRecordId, item.getEvidenceRecordId())) > 0;
      if (!exists) {
        evidenceMapper.insert(item);
      }
      atoms.put(item.getCapabilityAtomId(), item.getUserId());
    }
    atoms.forEach((atomId, userId) -> refreshInternal(userId, atomId));
  }

  @Transactional(rollbackFor = Exception.class)
  public void refresh(Long userId, String capabilityAtomId) {
    refreshInternal(userId, capabilityAtomId);
  }

  public List<CapabilityProfileView> list(Long userId) {
    Map<String, String> names = capabilityNames();
    return profileMapper.selectList(Wrappers.<CapabilityProfileEntity>lambdaQuery()
            .eq(CapabilityProfileEntity::getUserId, userId)
            .orderByAsc(CapabilityProfileEntity::getState)
            .orderByDesc(CapabilityProfileEntity::getUpdatedAt))
        .stream()
        .map(profile -> new CapabilityProfileView(
            profile.getCapabilityAtomId(),
            names.getOrDefault(profile.getCapabilityAtomId(), profile.getCapabilityAtomId()),
            profile.getState(), Boolean.TRUE.equals(profile.getReviewRequired()),
            value(profile.getEvidenceCount()),
            readIds(profile.getRecentEvidenceIdsJson()), profile.getLastEvidenceAt(),
            profile.getUpdatedAt()))
        .toList();
  }

  private void refreshInternal(Long userId, String capabilityAtomId) {
    List<CapabilityEvidenceEntity> evidence = evidenceMapper.selectList(
        Wrappers.<CapabilityEvidenceEntity>lambdaQuery()
            .eq(CapabilityEvidenceEntity::getUserId, userId)
            .eq(CapabilityEvidenceEntity::getCapabilityAtomId, capabilityAtomId)
            .orderByDesc(CapabilityEvidenceEntity::getOccurredAt)
            .last("LIMIT 100"));
    var projection = aggregator.project(evidence.stream().map(this::toObservation).toList());
    List<String> recentIds = projection.recent().stream()
        .map(Observation::evidenceRecordId)
        .toList();
    LocalDateTime lastAt = projection.recent().isEmpty()
        ? null : projection.recent().getFirst().occurredAt();

    CapabilityProfileEntity profile = profileMapper.selectOne(
        Wrappers.<CapabilityProfileEntity>lambdaQuery()
            .eq(CapabilityProfileEntity::getUserId, userId)
            .eq(CapabilityProfileEntity::getCapabilityAtomId, capabilityAtomId));
    LocalDateTime now = LocalDateTime.now();
    if (profile == null) {
      profile = CapabilityProfileEntity.builder()
          .userId(userId)
          .capabilityAtomId(capabilityAtomId)
          .createdAt(now)
          .build();
    }
    profile.setState(projection.state());
    profile.setReviewRequired(projection.reviewRequired());
    profile.setEvidenceCount(evidence.size());
    profile.setRecentEvidenceIdsJson(writeIds(recentIds));
    profile.setLastEvidenceAt(lastAt);
    profile.setUpdatedAt(now);
    if (profile.getId() == null) {
      try {
        profileMapper.insert(profile);
      } catch (DuplicateKeyException duplicate) {
        CapabilityProfileEntity existing = profileMapper.selectOne(
            Wrappers.<CapabilityProfileEntity>lambdaQuery()
                .eq(CapabilityProfileEntity::getUserId, userId)
                .eq(CapabilityProfileEntity::getCapabilityAtomId, capabilityAtomId));
        profile.setId(existing.getId());
        profile.setCreatedAt(existing.getCreatedAt());
        profileMapper.updateById(profile);
      }
    } else {
      profileMapper.updateById(profile);
    }
  }

  private Observation toObservation(CapabilityEvidenceEntity item) {
    return new Observation(
        item.getEvidenceRecordId(), item.getTechnicalScore(), item.getCompletenessScore(),
        item.getObjectivePassed(), item.getConfidence() == null
            ? 0.0d : item.getConfidence().doubleValue(),
        Boolean.TRUE.equals(item.getEligibleForPromotion()),
        Boolean.TRUE.equals(item.getHintUsed()), Boolean.TRUE.equals(item.getAnswerViewed()),
        value(item.getRedoCount()), item.getOccurredAt());
  }

  private Map<String, String> capabilityNames() {
    Map<String, String> result = new LinkedHashMap<>();
    atomMapper.selectList(Wrappers.<CapabilityAtomDefinitionEntity>lambdaQuery()
            .orderByDesc(CapabilityAtomDefinitionEntity::getCreatedAt))
        .forEach(atom -> result.putIfAbsent(atom.getAtomId(), atom.getName()));
    return result;
  }

  private String writeIds(List<String> ids) {
    try {
      return objectMapper.writeValueAsString(ids == null ? List.of() : ids);
    } catch (Exception e) {
      return "[]";
    }
  }

  private List<String> readIds(String json) {
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
}
