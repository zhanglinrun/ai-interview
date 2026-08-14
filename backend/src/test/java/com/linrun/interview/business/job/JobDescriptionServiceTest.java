package com.linrun.interview.business.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.document.service.impl.FileHashService;
import com.linrun.interview.business.vo.EvaluationRubricDTO;
import com.linrun.interview.business.service.CapabilityCatalogService;
import com.linrun.interview.business.service.EvaluationRubricService;
import com.linrun.interview.business.mapper.JobCapabilityMappingMapper;
import com.linrun.interview.business.mapper.JobDescriptionMapper;
import com.linrun.interview.business.job.CapabilityMappingSource;
import com.linrun.interview.business.job.JobCapabilityMappingEntity;
import com.linrun.interview.business.job.JobDescriptionEntity;
import com.linrun.interview.business.job.JobDescriptionStatus;
import com.linrun.interview.rag.service.EvidenceSnapshotService;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JD 版本与冻结边界")
class JobDescriptionServiceTest {

  private JobDescriptionMapper jobMapper;
  private JobCapabilityMappingMapper mappingMapper;
  private JobCapabilityMappingService mappingService;
  private EvaluationRubricService rubricService;
  private JobDescriptionService service;

  @BeforeEach
  void setUp() {
    jobMapper = mock(JobDescriptionMapper.class);
    mappingMapper = mock(JobCapabilityMappingMapper.class);
    mappingService = mock(JobCapabilityMappingService.class);
    rubricService = mock(EvaluationRubricService.class);
    service = new JobDescriptionService(
        jobMapper,
        mappingMapper,
        mappingService,
        mock(CapabilityCatalogService.class),
        rubricService,
        mock(EvidenceSnapshotService.class),
        new FileHashService(),
        JsonMapper.builder().findAndAddModules().build());
  }

  @Test
  @DisplayName("查询始终同时使用 id 与 userId，跨用户表现为不存在")
  void shouldRejectCrossUserRead() {
    when(jobMapper.selectOne(any())).thenReturn(null);

    assertThatThrownBy(() -> service.get(2L, 10L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不存在");
  }

  @Test
  @DisplayName("冻结时固化确认权重和 Rubric 版本")
  void shouldFreezeWeightsAndRubricVersion() {
    JobDescriptionEntity job = job(JobDescriptionStatus.ANALYZED);
    JobCapabilityMappingEntity first = mapping(1L, "0.70");
    JobCapabilityMappingEntity second = mapping(2L, "0.30");
    when(jobMapper.selectOne(any())).thenReturn(job);
    when(mappingService.listEntities(1L, 10L)).thenReturn(List.of(first, second));
    when(mappingService.list(1L, 10L)).thenReturn(List.of());
    when(rubricService.get("TECHNICAL_ANSWER", "1.0.0"))
        .thenReturn(new EvaluationRubricDTO("TECHNICAL_ANSWER", "1.0.0", List.of()));

    var frozen = service.freeze(1L, 10L);

    assertThat(frozen.status()).isEqualTo(JobDescriptionStatus.FROZEN);
    assertThat(job.getRubricVersionsJson()).contains("TECHNICAL_ANSWER", "1.0.0");
    assertThat(first.getConfirmedWeight()).isEqualByComparingTo("0.70");
    assertThat(second.getConfirmedWeight()).isEqualByComparingTo("0.30");
    verify(mappingMapper).updateById(first);
    verify(mappingMapper).updateById(second);
    verify(jobMapper).updateById(job);
  }

  @Test
  @DisplayName("已冻结 JD 删除时清正文证据但保留不可还原版本元数据")
  void shouldRedactFrozenJob() {
    JobDescriptionEntity job = job(JobDescriptionStatus.FROZEN);
    when(jobMapper.selectOne(any())).thenReturn(job);

    service.delete(1L, 10L);

    assertThat(job.getJdText()).isNull();
    assertThat(job.getStatus()).isEqualTo(JobDescriptionStatus.REDACTED);
    verify(mappingService).redactEvidence(1L, 10L);
    verify(jobMapper, never()).deleteById(org.mockito.ArgumentMatchers.<Serializable>any());
    verify(jobMapper).updateById(job);
  }

  private JobDescriptionEntity job(JobDescriptionStatus status) {
    return JobDescriptionEntity.builder()
        .id(10L)
        .userId(1L)
        .targetKey("target")
        .version(1)
        .title("Java 后端")
        .jdText("需要掌握 Java、数据库、缓存、消息队列与系统设计，并能够定位生产环境问题。")
        .contentHash("hash")
        .status(status)
        .templateCode("JAVA_BACKEND_BASELINE")
        .templateVersion("1.0.0")
        .build();
  }

  private JobCapabilityMappingEntity mapping(Long id, String weight) {
    return JobCapabilityMappingEntity.builder()
        .id(id)
        .userId(1L)
        .jobDescriptionId(10L)
        .atomId("ATOM_" + id)
        .atomVersion("1.0.0")
        .capabilityName("能力" + id)
        .mappingSource(CapabilityMappingSource.JD_LLM)
        .suggestedWeight(new BigDecimal(weight))
        .confidence(BigDecimal.ONE)
        .enabled(true)
        .build();
  }
}
