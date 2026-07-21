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

  @Test
  @DisplayName("overlap 不得达到 chunkSize 以免滑窗死循环")
  void resolveSplitParamClampsOversizedOverlap() {
    DocumentSplitParam resolved = service.resolveSplitParam(
        new DocumentSplitParam(SplitType.BROTHER.name(), 80, 100, null, null, null));

    assertThat(resolved.chunkSize()).isEqualTo(80);
    assertThat(resolved.overlap()).isEqualTo(79);
  }
}
