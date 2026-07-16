package com.linrun.interview.modules.jobtarget.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.modules.jobtarget.dto.ConfirmJobCapabilitiesRequest;
import com.linrun.interview.modules.jobtarget.dto.JobCapabilityMappingDTO;
import com.linrun.interview.modules.jobtarget.mapper.JobCapabilityMappingMapper;
import com.linrun.interview.modules.jobtarget.mapper.JobDescriptionMapper;
import com.linrun.interview.modules.jobtarget.model.CapabilityMappingSource;
import com.linrun.interview.modules.jobtarget.model.JobCapabilityMappingEntity;
import com.linrun.interview.modules.jobtarget.model.JobDescriptionEntity;
import com.linrun.interview.modules.jobtarget.model.JobDescriptionStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** JD 能力映射的原子写入边界，确保替换和用户确认不会留下半状态。 */
@Service
@RequiredArgsConstructor
public class JobCapabilityMappingService {

  private final JobCapabilityMappingMapper mappingMapper;
  private final JobDescriptionMapper jobDescriptionMapper;
  private final FileHashService fileHashService;

  public List<JobCapabilityMappingDTO> list(Long userId, Long jobDescriptionId) {
    return listEntities(userId, jobDescriptionId).stream().map(this::toDTO).toList();
  }

  public List<JobCapabilityMappingEntity> listEntities(Long userId, Long jobDescriptionId) {
    return mappingMapper.selectList(Wrappers.<JobCapabilityMappingEntity>lambdaQuery()
        .eq(JobCapabilityMappingEntity::getUserId, userId)
        .eq(JobCapabilityMappingEntity::getJobDescriptionId, jobDescriptionId)
        .orderByDesc(JobCapabilityMappingEntity::getEnabled)
        .orderByDesc(JobCapabilityMappingEntity::getConfirmedWeight)
        .orderByDesc(JobCapabilityMappingEntity::getSuggestedWeight)
        .orderByAsc(JobCapabilityMappingEntity::getId));
  }

  @Transactional(rollbackFor = Exception.class)
  public List<JobCapabilityMappingDTO> replaceAnalysis(
      Long userId,
      Long jobDescriptionId,
      List<MappingDraft> drafts
  ) {
    JobDescriptionEntity job = requireMutableJob(userId, jobDescriptionId);
    mappingMapper.delete(Wrappers.<JobCapabilityMappingEntity>lambdaQuery()
        .eq(JobCapabilityMappingEntity::getUserId, userId)
        .eq(JobCapabilityMappingEntity::getJobDescriptionId, jobDescriptionId));
    LocalDateTime now = LocalDateTime.now();
    for (MappingDraft draft : drafts) {
      mappingMapper.insert(JobCapabilityMappingEntity.builder()
          .userId(userId)
          .jobDescriptionId(jobDescriptionId)
          .atomId(draft.atomId())
          .atomVersion(draft.atomVersion())
          .capabilityName(draft.capabilityName())
          .mappingSource(draft.mappingSource())
          .evidenceText(draft.evidenceText())
          .evidenceStart(draft.evidenceStart())
          .evidenceEnd(draft.evidenceEnd())
          .suggestedWeight(draft.suggestedWeight())
          .confirmedWeight(null)
          .confidence(draft.confidence())
          .enabled(true)
          .createdAt(now)
          .updatedAt(now)
          .build());
    }
    job.setStatus(JobDescriptionStatus.ANALYZED);
    job.setUpdatedAt(now);
    jobDescriptionMapper.updateById(job);
    return list(userId, jobDescriptionId);
  }

  @Transactional(rollbackFor = Exception.class)
  public List<JobCapabilityMappingDTO> confirm(
      Long userId,
      Long jobDescriptionId,
      ConfirmJobCapabilitiesRequest request
  ) {
    JobDescriptionEntity job = requireMutableJob(userId, jobDescriptionId);
    List<JobCapabilityMappingEntity> mappings = listEntities(userId, jobDescriptionId);
    if (mappings.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请先分析 JD 再确认能力");
    }
    Map<Long, JobCapabilityMappingEntity> byId = new HashMap<>();
    mappings.forEach(mapping -> byId.put(mapping.getId(), mapping));
    var adjustedIds = new HashSet<Long>();
    for (var adjustment : request.adjustments()) {
      if (!adjustedIds.add(adjustment.mappingId())) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "能力映射不能重复提交: " + adjustment.mappingId());
      }
      JobCapabilityMappingEntity mapping = byId.get(adjustment.mappingId());
      if (mapping == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST,
            "能力映射不属于当前 JD: " + adjustment.mappingId());
      }
      mapping.setEnabled(adjustment.enabled());
      if (adjustment.weight() != null) {
        mapping.setConfirmedWeight(adjustment.weight());
      }
    }
    addTemporaryCapability(job, mappings, request.temporaryCapability());
    normalizeConfirmedWeights(mappings);
    LocalDateTime now = LocalDateTime.now();
    for (JobCapabilityMappingEntity mapping : mappings) {
      mapping.setUpdatedAt(now);
      if (mapping.getId() == null) {
        mappingMapper.insert(mapping);
      } else {
        mappingMapper.updateById(mapping);
      }
    }
    job.setStatus(JobDescriptionStatus.ANALYZED);
    job.setUpdatedAt(now);
    jobDescriptionMapper.updateById(job);
    return list(userId, jobDescriptionId);
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteForDraft(Long userId, Long jobDescriptionId) {
    mappingMapper.delete(Wrappers.<JobCapabilityMappingEntity>lambdaQuery()
        .eq(JobCapabilityMappingEntity::getUserId, userId)
        .eq(JobCapabilityMappingEntity::getJobDescriptionId, jobDescriptionId));
  }

  @Transactional(rollbackFor = Exception.class)
  public void redactEvidence(Long userId, Long jobDescriptionId) {
    List<JobCapabilityMappingEntity> mappings = listEntities(userId, jobDescriptionId);
    for (JobCapabilityMappingEntity mapping : mappings) {
      mapping.setEvidenceText(null);
      mapping.setEvidenceStart(null);
      mapping.setEvidenceEnd(null);
      mapping.setUpdatedAt(LocalDateTime.now());
      mappingMapper.updateById(mapping);
    }
  }

  private void addTemporaryCapability(
      JobDescriptionEntity job,
      List<JobCapabilityMappingEntity> mappings,
      ConfirmJobCapabilitiesRequest.TemporaryCapability temporary
  ) {
    if (temporary == null || temporary.name() == null || temporary.name().isBlank()) {
      return;
    }
    boolean alreadyExists = mappings.stream()
        .anyMatch(mapping -> Boolean.TRUE.equals(mapping.getEnabled())
            && (mapping.getMappingSource() == CapabilityMappingSource.USER_TEMPORARY
            || mapping.getMappingSource() == CapabilityMappingSource.JD_TEMPORARY));
    if (alreadyExists) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "每个 JD 最多补充一项临时能力");
    }
    String hash = fileHashService.calculateHash(
        (job.getId() + ":" + temporary.name().trim()).getBytes(StandardCharsets.UTF_8));
    BigDecimal weight = temporary.weight() == null ? new BigDecimal("0.10") : temporary.weight();
    mappings.add(JobCapabilityMappingEntity.builder()
        .userId(job.getUserId())
        .jobDescriptionId(job.getId())
        .atomId("JD_TEMP_" + hash.substring(0, 12).toUpperCase())
        .atomVersion("jd-" + job.getId() + "-v" + job.getVersion())
        .capabilityName(temporary.name().trim())
        .mappingSource(CapabilityMappingSource.USER_TEMPORARY)
        .suggestedWeight(weight)
        .confirmedWeight(weight)
        .confidence(BigDecimal.ONE)
        .enabled(true)
        .createdAt(LocalDateTime.now())
        .updatedAt(LocalDateTime.now())
        .build());
  }

  private void normalizeConfirmedWeights(List<JobCapabilityMappingEntity> mappings) {
    List<JobCapabilityMappingEntity> enabled = mappings.stream()
        .filter(mapping -> Boolean.TRUE.equals(mapping.getEnabled()))
        .toList();
    if (enabled.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "至少保留一个能力项");
    }
    BigDecimal total = enabled.stream()
        .map(mapping -> mapping.getConfirmedWeight() != null
            ? mapping.getConfirmedWeight() : mapping.getSuggestedWeight())
        .map(weight -> weight == null ? BigDecimal.ZERO : weight)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "能力权重之和必须大于 0");
    }
    BigDecimal normalizedTotal = BigDecimal.ZERO;
    for (int i = 0; i < enabled.size(); i++) {
      JobCapabilityMappingEntity mapping = enabled.get(i);
      BigDecimal raw = mapping.getConfirmedWeight() != null
          ? mapping.getConfirmedWeight() : mapping.getSuggestedWeight();
      BigDecimal normalized = i == enabled.size() - 1
          ? BigDecimal.ONE.subtract(normalizedTotal)
          : raw.divide(total, 6, RoundingMode.HALF_UP);
      mapping.setConfirmedWeight(normalized);
      normalizedTotal = normalizedTotal.add(normalized);
    }
    mappings.stream()
        .filter(mapping -> !Boolean.TRUE.equals(mapping.getEnabled()))
        .forEach(mapping -> mapping.setConfirmedWeight(null));
  }

  private JobDescriptionEntity requireMutableJob(Long userId, Long jobDescriptionId) {
    JobDescriptionEntity job = jobDescriptionMapper.selectOne(
        Wrappers.<JobDescriptionEntity>lambdaQuery()
            .eq(JobDescriptionEntity::getId, jobDescriptionId)
            .eq(JobDescriptionEntity::getUserId, userId));
    if (job == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "目标岗位不存在");
    }
    if (job.getStatus() == JobDescriptionStatus.FROZEN
        || job.getStatus() == JobDescriptionStatus.REDACTED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "该 JD 版本已冻结；请创建新版本后再修改");
    }
    return job;
  }

  public JobCapabilityMappingDTO toDTO(JobCapabilityMappingEntity entity) {
    return new JobCapabilityMappingDTO(
        entity.getId(), entity.getAtomId(), entity.getAtomVersion(), entity.getCapabilityName(),
        entity.getMappingSource(), entity.getEvidenceText(), entity.getEvidenceStart(),
        entity.getEvidenceEnd(), entity.getSuggestedWeight(), entity.getConfirmedWeight(),
        entity.getConfidence(), Boolean.TRUE.equals(entity.getEnabled()));
  }

  public record MappingDraft(
      String atomId,
      String atomVersion,
      String capabilityName,
      CapabilityMappingSource mappingSource,
      String evidenceText,
      Integer evidenceStart,
      Integer evidenceEnd,
      BigDecimal suggestedWeight,
      BigDecimal confidence
  ) {
  }
}
