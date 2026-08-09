package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.document.service.FileHashService;
import com.linrun.interview.github.service.GithubRepositoryService;
import com.linrun.interview.business.config.JobInterviewProperties;
import com.linrun.interview.business.vo.JobInterviewContracts.CreatePreparationRequest;
import com.linrun.interview.business.vo.JobInterviewContracts.PreparationView;
import com.linrun.interview.business.mapper.JobInterviewSessionMapper;
import com.linrun.interview.business.constant.JobCodingLanguage;
import com.linrun.interview.business.entity.PreparationRunEntity;
import com.linrun.interview.business.constant.PreparationStatus;
import com.linrun.interview.business.job.JobCapabilityMappingEntity;
import com.linrun.interview.business.job.JobDescriptionEntity;
import com.linrun.interview.business.job.JobDescriptionStatus;
import com.linrun.interview.business.job.JobCapabilityMappingService;
import com.linrun.interview.business.job.JobDescriptionService;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.business.mapper.ResumeEntityMapper;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位实战准备入口幂等性")
class JobInterviewPreparationServiceTest {

  @Mock
  private PreparationRunPersistenceService persistenceService;
  @Mock
  private PreparationTaskPublisher taskPublisher;
  @Mock
  private JobDescriptionService jobDescriptionService;
  @Mock
  private JobCapabilityMappingService mappingService;
  @Mock
  private GithubRepositoryService githubRepositoryService;
  @Mock
  private ResumeEntityMapper resumeMapper;
  @Mock
  private KnowledgeBaseEntityMapper knowledgeBaseMapper;
  @Mock
  private JobInterviewSessionMapper sessionMapper;
  @Mock
  private FileHashService fileHashService;

  private JobInterviewPreparationService service;

  @BeforeEach
  void setUp() {
    service = new JobInterviewPreparationService(
        persistenceService,
        taskPublisher,
        jobDescriptionService,
        mappingService,
        githubRepositoryService,
        resumeMapper,
        knowledgeBaseMapper,
        sessionMapper,
        fileHashService,
        new ObjectMapper().findAndRegisterModules(),
        new JobInterviewProperties());
  }

  @Test
  @DisplayName("重复请求命中准备中任务时不重复落库和发布")
  void shouldReusePreparingRunWithoutPublishingAgain() {
    stubFrozenJobAndMapping();
    when(fileHashService.calculateHash(any(byte[].class))).thenReturn("fingerprint");
    PreparationRunEntity preparing = PreparationRunEntity.builder()
        .runId("run-1")
        .userId(7L)
        .jobDescriptionId(11L)
        .includePersonalMaterials(false)
        .codingLanguage(JobCodingLanguage.JAVA21)
        .status(PreparationStatus.PREPARING)
        .createdAt(LocalDateTime.now())
        .build();
    when(persistenceService.findReusable(7L, "fingerprint"))
        .thenReturn(Optional.of(preparing));

    PreparationView result = service.create(7L, request());

    assertThat(result.runId()).isEqualTo("run-1");
    assertThat(result.status()).isEqualTo(PreparationStatus.PREPARING);
    assertThat(result.reused()).isTrue();
    verify(persistenceService, never()).create(any(PreparationRunEntity.class));
    verify(taskPublisher, never()).publish(any(String.class), any(Long.class));
  }

  @Test
  @DisplayName("历史会话不可恢复时应创建并发布全新的准备任务")
  void shouldCreateNewRunWhenNoReusableSessionExists() {
    stubFrozenJobAndMapping();
    when(fileHashService.calculateHash(any(byte[].class))).thenReturn("fingerprint");
    when(persistenceService.findReusable(7L, "fingerprint")).thenReturn(Optional.empty());
    when(persistenceService.create(any(PreparationRunEntity.class))).thenAnswer(invocation -> {
      PreparationRunEntity run = invocation.getArgument(0);
      run.setStatus(PreparationStatus.PREPARING);
      run.setCreatedAt(LocalDateTime.now());
      return run;
    });

    PreparationView result = service.create(7L, request());

    assertThat(result.runId()).isNotBlank();
    assertThat(result.status()).isEqualTo(PreparationStatus.PREPARING);
    assertThat(result.reused()).isFalse();
    verify(persistenceService).create(any(PreparationRunEntity.class));
    verify(taskPublisher).publish(result.runId(), 7L);
  }

  @Test
  @DisplayName("准备创建入口必须按用户串行化查询与创建窗口")
  void shouldDeclareUserScopedDistributedLock() throws Exception {
    Method method = JobInterviewPreparationService.class.getMethod(
        "create", Long.class, CreatePreparationRequest.class);

    DistributeLock lock = method.getAnnotation(DistributeLock.class);

    assertThat(lock).isNotNull();
    assertThat(lock.key()).contains("#userId");
    assertThat(lock.waitTime()).isPositive();
  }

  private void stubFrozenJobAndMapping() {
    JobDescriptionEntity job = JobDescriptionEntity.builder()
        .id(11L)
        .userId(7L)
        .version(2)
        .title("Java 后端工程师")
        .contentHash("job-hash")
        .status(JobDescriptionStatus.FROZEN)
        .templateCode("java-backend")
        .templateVersion("v1")
        .build();
    JobCapabilityMappingEntity mapping = JobCapabilityMappingEntity.builder()
        .atomId("java.concurrent")
        .atomVersion("v1")
        .confirmedWeight(BigDecimal.ONE)
        .enabled(true)
        .build();
    when(jobDescriptionService.requireOwned(7L, 11L)).thenReturn(job);
    when(mappingService.listEntities(7L, 11L)).thenReturn(List.of(mapping));
  }

  private CreatePreparationRequest request() {
    return new CreatePreparationRequest(
        11L, null, null, List.of(), false, JobCodingLanguage.JAVA21, false);
  }
}
