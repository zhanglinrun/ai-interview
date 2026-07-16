package com.linrun.interview.modules.algorithm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.modules.algorithm.mapper.AlgorithmContentImportMapper;
import com.linrun.interview.modules.algorithm.mapper.CodingProblemMapper;
import com.linrun.interview.modules.algorithm.mapper.CodingProblemVersionMapper;
import com.linrun.interview.modules.algorithm.model.AlgorithmContentImportEntity;
import com.linrun.interview.modules.algorithm.model.CodingProblemEntity;
import com.linrun.interview.modules.algorithm.model.CodingProblemVersionEntity;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Hot 100 内容导入")
class AlgorithmContentImportServiceTest {

  @Mock
  private AlgorithmContentImportMapper importMapper;
  @Mock
  private CodingProblemMapper problemMapper;
  @Mock
  private CodingProblemVersionMapper versionMapper;

  private AlgorithmContentImportService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    service = new AlgorithmContentImportService(
        objectMapper, new FileHashService(), new AlgorithmContentValidator(objectMapper),
        importMapper, problemMapper, versionMapper);
  }

  @Test
  @DisplayName("首次导入应落 100 题映射、20 个可运行版本和语义 checksum")
  void shouldImportPublishedCatalog() {
    when(importMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    AtomicLong ids = new AtomicLong(1L);
    when(problemMapper.insert(any(CodingProblemEntity.class))).thenAnswer(invocation -> {
      invocation.<CodingProblemEntity>getArgument(0).setId(ids.getAndIncrement());
      return 1;
    });

    var report = service.importClasspathCatalog();

    assertThat(report.alreadyImported()).isFalse();
    assertThat(report.problemCount()).isEqualTo(100);
    assertThat(report.enabledCount()).isEqualTo(20);
    verify(problemMapper, times(100)).insert(any(CodingProblemEntity.class));
    verify(versionMapper, times(20)).insert(any(CodingProblemVersionEntity.class));
    ArgumentCaptor<AlgorithmContentImportEntity> imported =
        ArgumentCaptor.forClass(AlgorithmContentImportEntity.class);
    verify(importMapper).insert(imported.capture());
    assertThat(imported.getValue().getChecksum()).matches("[a-f0-9]{64}");
    assertThat(imported.getValue().getContentVersion()).isEqualTo("hot100-2026.07-v1");
  }

  @Test
  @DisplayName("相同版本与 checksum 重启导入时应幂等返回")
  void shouldBeIdempotentForSameChecksum() {
    var loaded = service.load();
    var validation = service.validateClasspathCatalog();
    AlgorithmContentImportEntity existing = AlgorithmContentImportEntity.builder()
        .contentVersion(loaded.content().contentVersion())
        .checksum(validation.calculatedChecksum())
        .build();
    when(importMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    var report = service.importClasspathCatalog();

    assertThat(report.alreadyImported()).isTrue();
    verify(problemMapper, never()).insert(any(CodingProblemEntity.class));
    verify(versionMapper, never()).insert(any(CodingProblemVersionEntity.class));
  }

  @Test
  @DisplayName("已发布版本的 checksum 变化时禁止静默覆盖")
  void shouldRejectChangedPublishedVersion() {
    when(importMapper.selectOne(any(Wrapper.class))).thenReturn(
        AlgorithmContentImportEntity.builder()
            .contentVersion("hot100-2026.07-v1")
            .checksum("0".repeat(64))
            .build());

    assertThatThrownBy(service::importClasspathCatalog)
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("禁止覆盖");
    verify(problemMapper, never()).insert(any(CodingProblemEntity.class));
  }
}
