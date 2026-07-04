package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.constant.SplitType;
import com.linrun.interview.modules.knowledgebase.model.DocumentSplitParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("知识库切块服务测试")
class KnowledgeBaseChunkingServiceTest {

  private final KnowledgeBaseChunkingService service = new KnowledgeBaseChunkingService(new KnowledgeBaseQueryProperties());

  @Test
  @DisplayName("空 splitType 应回落默认 BROTHER")
  void resolveSplitParamUsesDefaultWhenSplitTypeMissing() {
    DocumentSplitParam resolved = service.resolveSplitParam(new DocumentSplitParam(null, null, null, null, null, null));

    assertThat(resolved.splitType()).isEqualTo(SplitType.BROTHER.name());
    assertThat(resolved.chunkSize()).isGreaterThan(0);
  }

  @Test
  @DisplayName("缺省 splitType 时保留自定义 chunkSize")
  void resolveSplitParamKeepsCustomChunkSize() {
    DocumentSplitParam resolved = service.resolveSplitParam(
        new DocumentSplitParam(null, 512, null, null, null, null));

    assertThat(resolved.splitType()).isEqualTo(SplitType.BROTHER.name());
    assertThat(resolved.chunkSize()).isEqualTo(512);
  }
}
