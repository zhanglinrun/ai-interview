package com.linrun.interview.document.service;
import com.linrun.interview.document.service.impl.KnowledgeBaseChunkingService;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;


import com.linrun.interview.document.constant.SplitType;
import com.linrun.interview.document.vo.DocumentSplitParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("知识库切块服务测试")
class KnowledgeBaseChunkingServiceTest {

  private final KnowledgeBaseChunkingService service = new KnowledgeBaseChunkingService(new KnowledgeBaseQueryProperties());

  @Test
  @DisplayName("空 splitType 应回落默认 PARENT_CHILD")
  void resolveSplitParamUsesDefaultWhenSplitTypeMissing() {
    DocumentSplitParam resolved = service.resolveSplitParam(new DocumentSplitParam(null, null, null, null, null, null));

    assertThat(resolved.splitType()).isEqualTo(SplitType.PARENT_CHILD.name());
    assertThat(resolved.chunkSize()).isGreaterThan(0);
  }

  @Test
  @DisplayName("缺省 splitType 时保留自定义 chunkSize")
  void resolveSplitParamKeepsCustomChunkSize() {
    DocumentSplitParam resolved = service.resolveSplitParam(
        new DocumentSplitParam(null, 512, null, null, null, null));

    assertThat(resolved.splitType()).isEqualTo(SplitType.PARENT_CHILD.name());
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

  @Test
  @DisplayName("默认父子策略对超长标题段保留父块并生成子块")
  void parentChildStrategyProducesParentAndChildren() {
    String content = "# JVM\n" + "垃圾回收原理与分代回收机制。".repeat(80);

    List<dev.langchain4j.data.segment.TextSegment> chunks = service.split(
        content,
        new DocumentSplitParam(SplitType.PARENT_CHILD.name(), 80, 10, null, null, null));

    assertThat(chunks).anySatisfy(chunk ->
        assertThat(chunk.metadata().getInteger("skipEmbedding")).isEqualTo(1));
    assertThat(chunks).anySatisfy(chunk ->
        assertThat(chunk.metadata().getString("parentChunkId")).isNotBlank());
  }
}
