package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.github.model.GithubRepositoryEntity;
import com.linrun.interview.github.service.GithubRepositoryService;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.entity.PreparationRunEntity;
import com.linrun.interview.business.job.JobCapabilityMappingEntity;
import com.linrun.interview.business.job.JobDescriptionEntity;
import com.linrun.interview.business.job.JobDescriptionStatus;
import com.linrun.interview.business.job.JobCapabilityMappingService;
import com.linrun.interview.business.job.JobDescriptionService;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.business.mapper.ResumeEntityMapper;
import com.linrun.interview.business.entity.ResumeEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** RabbitMQ 消费者调用的非事务编排；所有外部调用发生在短事务之间。 */
@Service
@RequiredArgsConstructor
public class JobInterviewPreparationProcessor {

  private final PreparationRunPersistenceService runPersistence;
  private final JobDescriptionService jobDescriptionService;
  private final JobCapabilityMappingService mappingService;
  private final ResumeEntityMapper resumeMapper;
  private final KnowledgeBaseEntityMapper knowledgeBaseMapper;
  private final GithubRepositoryService githubRepositoryService;
  private final JobInterviewPlanBuilder planBuilder;
  private final JobInterviewSessionPersistenceService sessionPersistence;

  public void process(String runId, Long userId) {
    PreparationRunEntity run = runPersistence.requireOwned(userId, runId);
    JobDescriptionEntity job = jobDescriptionService.requireOwned(userId, run.getJobDescriptionId());
    if (job.getStatus() != JobDescriptionStatus.FROZEN) {
      throw new BusinessException(ErrorCode.INTERVIEW_PREPARATION_NOT_READY,
          "准备期间 JD 已不再是冻结版本");
    }
    List<JobCapabilityMappingEntity> mappings = mappingService
        .listEntities(userId, job.getId()).stream()
        .filter(mapping -> Boolean.TRUE.equals(mapping.getEnabled()))
        .toList();
    if (mappings.isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_PREPARATION_NOT_READY,
          "冻结 JD 的能力映射已不可用");
    }
    ResumeEntity resume = findResume(userId, run.getResumeId());
    GithubRepositoryEntity github = findGithub(userId, run.getGithubRepositoryId());
    List<KnowledgeBaseEntity> knowledgeBases = findKnowledgeBases(
        userId, run.getKnowledgeBaseIdsJson());

    JobInterviewPlanBuilder.PreparedPlan prepared = planBuilder.build(
        run, job, mappings, resume, github, knowledgeBases);
    JobInterviewSessionEntity session = sessionPersistence.createPreparedSession(
        run, job, github, prepared);
    runPersistence.markReady(
        runId,
        userId,
        session.getSessionId(),
        sessionPersistence.writeJson(prepared.snapshot()),
        sessionPersistence.writeJson(prepared.snapshot().evidenceSnapshotIds()),
        sessionPersistence.writeJson(prepared.dependencyStatus()),
        sessionPersistence.writeJson(prepared.snapshot().degradedReasons()));
  }

  private ResumeEntity findResume(Long userId, Long resumeId) {
    if (resumeId == null) {
      return null;
    }
    return resumeMapper.selectOne(Wrappers.<ResumeEntity>lambdaQuery()
        .eq(ResumeEntity::getUserId, userId)
        .eq(ResumeEntity::getId, resumeId));
  }

  private GithubRepositoryEntity findGithub(Long userId, Long repositoryId) {
    if (repositoryId == null) {
      return null;
    }
    try {
      return githubRepositoryService.require(userId, repositoryId);
    } catch (BusinessException e) {
      return null;
    }
  }

  private List<KnowledgeBaseEntity> findKnowledgeBases(Long userId, String idsJson) {
    List<Long> ids = sessionPersistence.readJson(
        idsJson, new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {}, List.of());
    if (ids.isEmpty()) {
      return List.of();
    }
    return knowledgeBaseMapper.selectList(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
        .eq(KnowledgeBaseEntity::getUserId, userId)
        .in(KnowledgeBaseEntity::getId, ids));
  }
}
