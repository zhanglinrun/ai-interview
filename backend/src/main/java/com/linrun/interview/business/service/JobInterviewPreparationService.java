package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.document.service.FileHashService;
import com.linrun.interview.github.model.GithubRepositoryEntity;
import com.linrun.interview.github.service.GithubRepositoryService;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.config.JobInterviewProperties;
import com.linrun.interview.business.vo.JobInterviewContracts.CreatePreparationRequest;
import com.linrun.interview.business.vo.JobInterviewContracts.PreparationView;
import com.linrun.interview.business.vo.JobInterviewContracts.StageView;
import com.linrun.interview.business.mapper.JobInterviewSessionMapper;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.JobInterviewStage;
import com.linrun.interview.business.entity.PreparationRunEntity;
import com.linrun.interview.business.constant.PreparationStatus;
import com.linrun.interview.business.job.JobCapabilityMappingEntity;
import com.linrun.interview.business.job.JobDescriptionEntity;
import com.linrun.interview.business.job.JobDescriptionStatus;
import com.linrun.interview.business.job.JobCapabilityMappingService;
import com.linrun.interview.business.job.JobDescriptionService;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.business.mapper.ResumeEntityMapper;
import com.linrun.interview.business.entity.ResumeEntity;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JobInterviewPreparationService {

  private final PreparationRunPersistenceService persistenceService;
  private final PreparationTaskPublisher taskPublisher;
  private final JobDescriptionService jobDescriptionService;
  private final JobCapabilityMappingService mappingService;
  private final GithubRepositoryService githubRepositoryService;
  private final ResumeEntityMapper resumeMapper;
  private final KnowledgeBaseEntityMapper knowledgeBaseMapper;
  private final JobInterviewSessionMapper sessionMapper;
  private final FileHashService fileHashService;
  private final ObjectMapper objectMapper;
  private final JobInterviewProperties properties;

  @DistributeLock(
      key = "'job-interview:preparation:' + #userId",
      waitTime = 10,
      leaseTime = 30,
      message = "岗位实战准备任务正在创建，请稍后重试"
  )
  public PreparationView create(Long userId, CreatePreparationRequest request) {
    JobDescriptionEntity job = requireFrozenJob(userId, request.jobDescriptionId());
    List<JobCapabilityMappingEntity> mappings = requireMappings(userId, job.getId());
    ResumeEntity resume = validateResume(userId, request.resumeId());
    GithubRepositoryEntity github = validateGithub(userId, request.githubRepositoryId());
    List<KnowledgeBaseEntity> knowledgeBases = validateKnowledgeBases(
        userId, request.knowledgeBaseIds(), request.includePersonalMaterials());
    String inputJson = writeJson(inputSnapshot(job, mappings, resume, github, knowledgeBases, request));
    String fingerprint = fileHashService.calculateHash(inputJson.getBytes(StandardCharsets.UTF_8));

    if (!request.regenerate()) {
      Optional<PreparationRunEntity> reusable = persistenceService.findReusable(userId, fingerprint);
      if (reusable.isPresent()) {
        return toView(reusable.get(), job, true);
      }
    }

    PreparationRunEntity run = PreparationRunEntity.builder()
        .runId(UUID.randomUUID().toString())
        .userId(userId)
        .jobDescriptionId(job.getId())
        .resumeId(resume == null ? null : resume.getId())
        .githubRepositoryId(github == null ? null : github.getId())
        .knowledgeBaseIdsJson(writeJson(knowledgeBases.stream()
            .map(KnowledgeBaseEntity::getId).toList()))
        .includePersonalMaterials(request.includePersonalMaterials())
        .codingLanguage(request.codingLanguage())
        .fingerprint(fingerprint)
        .inputSnapshotJson(inputJson)
        .build();
    persistenceService.create(run);
    taskPublisher.publish(run.getRunId(), userId);
    return toView(run, job, false);
  }

  public PreparationView get(Long userId, String runId) {
    PreparationRunEntity run = persistenceService.requireOwned(userId, runId);
    JobDescriptionEntity job = jobDescriptionService.requireOwned(userId, run.getJobDescriptionId());
    return toView(run, job, false);
  }

  private JobDescriptionEntity requireFrozenJob(Long userId, Long jobDescriptionId) {
    JobDescriptionEntity job = jobDescriptionService.requireOwned(userId, jobDescriptionId);
    if (job.getStatus() != JobDescriptionStatus.FROZEN) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "岗位实战必须绑定已确认并冻结的 JD 版本");
    }
    return job;
  }

  private List<JobCapabilityMappingEntity> requireMappings(Long userId, Long jobDescriptionId) {
    List<JobCapabilityMappingEntity> mappings = mappingService.listEntities(userId, jobDescriptionId)
        .stream()
        .filter(mapping -> Boolean.TRUE.equals(mapping.getEnabled()))
        .toList();
    if (mappings.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "冻结 JD 没有可用能力映射");
    }
    return mappings;
  }

  private ResumeEntity validateResume(Long userId, Long resumeId) {
    if (resumeId == null) {
      return null;
    }
    ResumeEntity resume = resumeMapper.selectOne(Wrappers.<ResumeEntity>lambdaQuery()
        .eq(ResumeEntity::getId, resumeId)
        .eq(ResumeEntity::getUserId, userId));
    if (resume == null) {
      throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
    }
    return resume;
  }

  private GithubRepositoryEntity validateGithub(Long userId, Long repositoryId) {
    return repositoryId == null ? null : githubRepositoryService.require(userId, repositoryId);
  }

  private List<KnowledgeBaseEntity> validateKnowledgeBases(
      Long userId,
      List<Long> ids,
      boolean includePersonalMaterials
  ) {
    if (!includePersonalMaterials && ids != null && !ids.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "关闭个人知识增强时不能绑定个人知识库");
    }
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    List<Long> distinct = ids.stream().distinct().toList();
    List<KnowledgeBaseEntity> entities = knowledgeBaseMapper.selectList(
        Wrappers.<KnowledgeBaseEntity>lambdaQuery()
            .eq(KnowledgeBaseEntity::getUserId, userId)
            .in(KnowledgeBaseEntity::getId, distinct));
    if (entities.size() != distinct.size()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
          "个人知识库不存在或无权访问");
    }
    Map<Long, KnowledgeBaseEntity> byId = new LinkedHashMap<>();
    entities.forEach(entity -> byId.put(entity.getId(), entity));
    return distinct.stream().map(byId::get).toList();
  }

  private Map<String, Object> inputSnapshot(
      JobDescriptionEntity job,
      List<JobCapabilityMappingEntity> mappings,
      ResumeEntity resume,
      GithubRepositoryEntity github,
      List<KnowledgeBaseEntity> knowledgeBases,
      CreatePreparationRequest request
  ) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("jobDescriptionId", job.getId());
    snapshot.put("jobDescriptionVersion", job.getVersion());
    snapshot.put("jobContentHash", job.getContentHash());
    snapshot.put("templateCode", job.getTemplateCode());
    snapshot.put("templateVersion", job.getTemplateVersion());
    snapshot.put("capabilities", mappings.stream().map(mapping -> Map.of(
        "atomId", mapping.getAtomId(),
        "atomVersion", mapping.getAtomVersion(),
        "weight", mapping.getConfirmedWeight())).toList());
    snapshot.put("resumeHash", resume == null ? "" : resume.getFileHash());
    snapshot.put("githubSha", github == null ? "" : github.getFixedCommitSha());
    snapshot.put("knowledgeBases", knowledgeBases.stream().map(kb -> Map.of(
        "id", kb.getId(),
        "version", kb.getCurrentVersionId() == null ? 0L : kb.getCurrentVersionId(),
        "hash", kb.getFileHash() == null ? "" : kb.getFileHash())).toList());
    snapshot.put("includePersonalMaterials", request.includePersonalMaterials());
    snapshot.put("codingLanguage", request.codingLanguage().name());
    snapshot.put("promptVersion", properties.getPromptVersion());
    return snapshot;
  }

  private PreparationView toView(
      PreparationRunEntity run,
      JobDescriptionEntity job,
      boolean reused
  ) {
    Map<String, String> dependencies = readJson(
        run.getDependencyStatusJson(), new TypeReference<Map<String, String>>() {}, Map.of());
    List<String> degraded = readJson(
        run.getDegradedReasonsJson(), new TypeReference<List<String>>() {}, List.of());
    Long sessionVersion = null;
    if (run.getSessionId() != null) {
      JobInterviewSessionEntity session = sessionMapper.selectOne(
          Wrappers.<JobInterviewSessionEntity>lambdaQuery()
              .eq(JobInterviewSessionEntity::getUserId, run.getUserId())
              .eq(JobInterviewSessionEntity::getSessionId, run.getSessionId()));
      sessionVersion = session == null ? null : session.getSessionVersion();
    }
    List<StageView> stages = new ArrayList<>();
    for (JobInterviewStage stage : JobInterviewStage.values()) {
      stages.add(new StageView(stage, Math.toIntExact(stage.budget().toSeconds())));
    }
    return new PreparationView(
        run.getRunId(), run.getStatus(), run.getJobDescriptionId(), job.getTitle(),
        job.getTemplateCode(), job.getTemplateVersion(), run.getCodingLanguage(),
        Boolean.TRUE.equals(run.getIncludePersonalMaterials()), run.getResumeId() != null,
        run.getGithubRepositoryId() != null, stages, degraded, dependencies,
        run.getSessionId(), sessionVersion, run.getFailureCode(), run.getFailureDetail(),
        run.getCreatedAt(), run.getCompletedAt(), reused);
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化岗位实战准备快照失败", e);
    }
  }

  private <T> T readJson(String json, TypeReference<T> type, T fallback) {
    if (json == null || json.isBlank()) {
      return fallback;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception e) {
      return fallback;
    }
  }
}
