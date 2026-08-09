package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.business.mapper.CapabilityAtomDefinitionMapper;
import com.linrun.interview.business.mapper.CapabilityContentImportMapper;
import com.linrun.interview.business.mapper.CapabilityTemplateMapper;
import com.linrun.interview.business.mapper.EvaluationRubricMapper;
import com.linrun.interview.business.mapper.PlatformKnowledgeManifestMapper;
import com.linrun.interview.business.mapper.QuestionTemplateMapper;
import com.linrun.interview.business.mapper.TemplateCapabilityMapper;
import com.linrun.interview.business.entity.CapabilityContentImportEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("版本化能力内容幂等导入")
class ContentImportServiceTest {

  private CapabilityContentImportMapper importMapper;
  private CapabilityTemplateMapper templateMapper;
  private CapabilityAtomDefinitionMapper atomMapper;
  private TemplateCapabilityMapper bindingMapper;
  private QuestionTemplateMapper questionMapper;
  private EvaluationRubricMapper rubricMapper;
  private PlatformKnowledgeManifestMapper knowledgeMapper;
  private ContentImportService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    importMapper = mock(CapabilityContentImportMapper.class);
    templateMapper = mock(CapabilityTemplateMapper.class);
    atomMapper = mock(CapabilityAtomDefinitionMapper.class);
    bindingMapper = mock(TemplateCapabilityMapper.class);
    questionMapper = mock(QuestionTemplateMapper.class);
    rubricMapper = mock(EvaluationRubricMapper.class);
    knowledgeMapper = mock(PlatformKnowledgeManifestMapper.class);
    service = new ContentImportService(
        objectMapper,
        new CapabilityContentValidator(objectMapper),
        importMapper,
        templateMapper,
        atomMapper,
        bindingMapper,
        questionMapper,
        rubricMapper,
        knowledgeMapper);
  }

  @Test
  @DisplayName("相同 contentVersion 与 checksum 重复导入直接跳过")
  void shouldSkipSameVersionAndChecksum() {
    var content = service.loadClasspathCatalog();
    when(importMapper.selectOne(any())).thenReturn(CapabilityContentImportEntity.builder()
        .contentVersion(content.contentVersion())
        .checksum(content.checksum())
        .build());

    var report = service.importClasspathCatalog();

    assertThat(report.alreadyImported()).isTrue();
    verifyNoInteractions(templateMapper, atomMapper, bindingMapper, questionMapper,
        rubricMapper, knowledgeMapper);
  }

  @Test
  @DisplayName("相同 contentVersion 不同 checksum 禁止覆盖")
  void shouldRejectVersionOverwrite() {
    var content = service.loadClasspathCatalog();
    when(importMapper.selectOne(any())).thenReturn(CapabilityContentImportEntity.builder()
        .contentVersion(content.contentVersion())
        .checksum("sha256:different")
        .build());

    assertThatThrownBy(service::importClasspathCatalog)
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("禁止覆盖");
  }
}
