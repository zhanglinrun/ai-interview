package com.linrun.interview.business.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.document.service.impl.FileHashService;
import com.linrun.interview.business.vo.CapabilityTemplateDTO;
import com.linrun.interview.business.service.CapabilityCatalogService;
import com.linrun.interview.business.service.EvaluationRubricService;
import com.linrun.interview.business.job.CreateJobDescriptionRequest;
import com.linrun.interview.business.job.CreateJobDescriptionVersionRequest;
import com.linrun.interview.business.job.JobDescriptionDTO;
import com.linrun.interview.business.mapper.JobCapabilityMappingMapper;
import com.linrun.interview.business.mapper.JobDescriptionMapper;
import com.linrun.interview.business.job.JobCapabilityMappingEntity;
import com.linrun.interview.business.job.JobDescriptionEntity;
import com.linrun.interview.business.job.JobDescriptionStatus;
import com.linrun.interview.rag.service.EvidenceSnapshotService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 目标岗位与 JD 不可变版本的用户隔离 CRUD。 */
@Service
@RequiredArgsConstructor
public class JobDescriptionService {

  private static final String DEFAULT_RUBRIC_CODE = "TECHNICAL_ANSWER";
  private static final String DEFAULT_RUBRIC_VERSION = "1.0.0";

  private final JobDescriptionMapper jobDescriptionMapper;
  private final JobCapabilityMappingMapper mappingMapper;
  private final JobCapabilityMappingService mappingService;
  private final CapabilityCatalogService catalogService;
  private final EvaluationRubricService rubricService;
  private final EvidenceSnapshotService evidenceSnapshotService;
  private final FileHashService fileHashService;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  public JobDescriptionDTO create(Long userId, CreateJobDescriptionRequest request) {
    requireUserId(userId);
    CapabilityTemplateDTO template = catalogService.getPublishedTemplate(request.jobTrack());
    String jdText = request.jdText().trim();
    LocalDateTime now = LocalDateTime.now();
    JobDescriptionEntity entity = JobDescriptionEntity.builder()
        .userId(userId)
        .targetKey(UUID.randomUUID().toString())
        .version(1)
        .title(request.title().trim())
        .company(trimToNull(request.company()))
        .jobTrack(request.jobTrack())
        .jdText(jdText)
        .sourceUrl(validateSourceUrl(request.sourceUrl()))
        .contentHash(hash(jdText))
        .status(JobDescriptionStatus.DRAFT)
        .templateCode(template.templateCode())
        .templateVersion(template.version())
        .createdAt(now)
        .updatedAt(now)
        .build();
    jobDescriptionMapper.insert(entity);
    return toDTO(entity, true, false);
  }

  @Transactional(rollbackFor = Exception.class)
  public JobDescriptionDTO createVersion(
      Long userId,
      Long previousVersionId,
      CreateJobDescriptionVersionRequest request
  ) {
    JobDescriptionEntity previous = requireOwned(userId, previousVersionId);
    JobDescriptionEntity latest = latestVersion(userId, previous.getTargetKey());
    String jdText = request.jdText().trim();
    String hash = hash(jdText);
    if (latest.getContentHash().equals(hash)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "JD 内容未变化，无需创建新版本");
    }
    CapabilityTemplateDTO template = catalogService.getPublishedTemplate(previous.getJobTrack());
    LocalDateTime now = LocalDateTime.now();
    JobDescriptionEntity entity = JobDescriptionEntity.builder()
        .userId(userId)
        .targetKey(previous.getTargetKey())
        .version(latest.getVersion() + 1)
        .title(previous.getTitle())
        .company(previous.getCompany())
        .jobTrack(previous.getJobTrack())
        .jdText(jdText)
        .sourceUrl(validateSourceUrl(request.sourceUrl()))
        .contentHash(hash)
        .status(JobDescriptionStatus.DRAFT)
        .templateCode(template.templateCode())
        .templateVersion(template.version())
        .createdAt(now)
        .updatedAt(now)
        .build();
    jobDescriptionMapper.insert(entity);
    return toDTO(entity, true, false);
  }

  public List<JobDescriptionDTO> list(Long userId) {
    requireUserId(userId);
    return jobDescriptionMapper.selectList(Wrappers.<JobDescriptionEntity>lambdaQuery()
            .eq(JobDescriptionEntity::getUserId, userId)
            .orderByDesc(JobDescriptionEntity::getUpdatedAt)
            .orderByDesc(JobDescriptionEntity::getVersion))
        .stream()
        .map(entity -> toDTO(entity, false, false))
        .toList();
  }

  public JobDescriptionDTO get(Long userId, Long id) {
    return toDTO(requireOwned(userId, id), true, true);
  }

  public JobDescriptionEntity requireOwned(Long userId, Long id) {
    requireUserId(userId);
    JobDescriptionEntity entity = jobDescriptionMapper.selectOne(
        Wrappers.<JobDescriptionEntity>lambdaQuery()
            .eq(JobDescriptionEntity::getUserId, userId)
            .eq(JobDescriptionEntity::getId, id));
    if (entity == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "目标岗位不存在");
    }
    return entity;
  }

  @Transactional(rollbackFor = Exception.class)
  public JobDescriptionDTO freeze(Long userId, Long id) {
    JobDescriptionEntity job = requireOwned(userId, id);
    if (job.getStatus() == JobDescriptionStatus.FROZEN) {
      return toDTO(job, true, true);
    }
    if (job.getStatus() != JobDescriptionStatus.ANALYZED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请先分析并确认 JD 能力后再冻结");
    }
    List<JobCapabilityMappingEntity> mappings = mappingService.listEntities(userId, id).stream()
        .filter(mapping -> Boolean.TRUE.equals(mapping.getEnabled()))
        .toList();
    if (mappings.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "冻结前至少需要一个有效能力项");
    }
    normalizeForFreeze(mappings);
    for (JobCapabilityMappingEntity mapping : mappings) {
      mapping.setUpdatedAt(LocalDateTime.now());
      mappingMapper.updateById(mapping);
    }
    rubricService.get(DEFAULT_RUBRIC_CODE, DEFAULT_RUBRIC_VERSION);
    job.setRubricVersionsJson(writeJson(List.of(Map.of(
        "rubricCode", DEFAULT_RUBRIC_CODE,
        "version", DEFAULT_RUBRIC_VERSION))));
    job.setStatus(JobDescriptionStatus.FROZEN);
    job.setFrozenAt(LocalDateTime.now());
    job.setUpdatedAt(LocalDateTime.now());
    jobDescriptionMapper.updateById(job);
    return toDTO(job, true, true);
  }

  @Transactional(rollbackFor = Exception.class)
  public void delete(Long userId, Long id) {
    JobDescriptionEntity job = requireOwned(userId, id);
    evidenceSnapshotService.markSourceUnavailable(
        userId,
        DataDomain.JOB,
        String.valueOf(id),
        String.valueOf(job.getVersion()));
    if (job.getStatus() == JobDescriptionStatus.FROZEN
        || job.getStatus() == JobDescriptionStatus.REDACTED) {
      if (job.getStatus() != JobDescriptionStatus.REDACTED) {
        mappingService.redactEvidence(userId, id);
        job.setJdText(null);
        job.setSourceUrl(null);
        job.setStatus(JobDescriptionStatus.REDACTED);
        job.setUpdatedAt(LocalDateTime.now());
        jobDescriptionMapper.updateById(job);
      }
      return;
    }
    mappingService.deleteForDraft(userId, id);
    jobDescriptionMapper.deleteById(id);
  }

  private void normalizeForFreeze(List<JobCapabilityMappingEntity> mappings) {
    BigDecimal total = mappings.stream()
        .map(mapping -> mapping.getConfirmedWeight() != null
            ? mapping.getConfirmedWeight() : mapping.getSuggestedWeight())
        .map(weight -> weight == null ? BigDecimal.ZERO : weight)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "能力权重之和必须大于 0");
    }
    BigDecimal accumulated = BigDecimal.ZERO;
    for (int i = 0; i < mappings.size(); i++) {
      JobCapabilityMappingEntity mapping = mappings.get(i);
      BigDecimal raw = mapping.getConfirmedWeight() != null
          ? mapping.getConfirmedWeight() : mapping.getSuggestedWeight();
      BigDecimal normalized = i == mappings.size() - 1
          ? BigDecimal.ONE.subtract(accumulated)
          : raw.divide(total, 6, RoundingMode.HALF_UP);
      mapping.setConfirmedWeight(normalized);
      accumulated = accumulated.add(normalized);
    }
  }

  private JobDescriptionEntity latestVersion(Long userId, String targetKey) {
    JobDescriptionEntity latest = jobDescriptionMapper.selectOne(
        Wrappers.<JobDescriptionEntity>lambdaQuery()
            .eq(JobDescriptionEntity::getUserId, userId)
            .eq(JobDescriptionEntity::getTargetKey, targetKey)
            .orderByDesc(JobDescriptionEntity::getVersion)
            .last("LIMIT 1"));
    if (latest == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "目标岗位版本不存在");
    }
    return latest;
  }

  private JobDescriptionDTO toDTO(
      JobDescriptionEntity entity,
      boolean includeContent,
      boolean includeCapabilities
  ) {
    return new JobDescriptionDTO(
        entity.getId(), entity.getTargetKey(), entity.getVersion(), entity.getTitle(),
        entity.getCompany(), entity.getJobTrack(), includeContent ? entity.getJdText() : null,
        entity.getSourceUrl(), entity.getContentHash(), entity.getStatus(),
        entity.getTemplateCode(), entity.getTemplateVersion(), entity.getFrozenAt(),
        entity.getCreatedAt(), includeCapabilities
            ? mappingService.list(entity.getUserId(), entity.getId()) : List.of());
  }

  private String validateSourceUrl(String sourceUrl) {
    String value = trimToNull(sourceUrl);
    if (value == null) {
      return null;
    }
    try {
      URI uri = URI.create(value);
      if (uri.getHost() == null
          || !("http".equalsIgnoreCase(uri.getScheme())
          || "https".equalsIgnoreCase(uri.getScheme()))) {
        throw new IllegalArgumentException("unsupported url");
      }
      return value;
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "JD 来源 URL 必须是有效的 HTTP(S) 地址");
    }
  }

  private String hash(String content) {
    return fileHashService.calculateHash(content.getBytes(StandardCharsets.UTF_8));
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "冻结 Rubric 版本失败", e);
    }
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private void requireUserId(Long userId) {
    if (userId == null || userId <= 0) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
    }
  }
}
