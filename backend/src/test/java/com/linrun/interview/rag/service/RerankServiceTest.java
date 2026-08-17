package com.linrun.interview.rag.service;

import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RerankService 本地 ONNX 测试")
class RerankServiceTest {

  @Test
  @DisplayName("classpath 模型存在时应成功加载并打分")
  void loadsRealModelWhenPresent() {
    var loader = new DefaultResourceLoader();
    Assumptions.assumeTrue(
        loader.getResource("classpath:model/bge-reranker-model/model_quantized.onnx").exists()
            && loader.getResource("classpath:model/bge-reranker-model/tokenizer.json").exists(),
        "本地 BGE 模型文件未就绪");

    RerankService service = new RerankService(new KnowledgeBaseQueryProperties());
    assertThat(service.isEnabled()).isTrue();
    assertThat(service.warmupLocalModel()).isTrue();

    var scores = service.scoreAll(
        List.of(
            TextSegment.from("Java 是面向对象语言，JVM 提供跨平台能力。"),
            TextSegment.from("Python 常用于数据科学与 AI。")),
        "什么是 Java 虚拟机？").content();

    assertThat(scores).hasSize(2);
    assertThat(scores.get(0)).isGreaterThan(scores.get(1));
  }

  @Test
  @DisplayName("本地模型不可用时应返回等分且 isEnabled=false")
  void returnsZeroScoresWhenUnavailable() {
    KnowledgeBaseQueryProperties props = new KnowledgeBaseQueryProperties();
    props.getRerank().setEnabled(true);
    props.getRerank().getLocal().setModelPath("classpath:missing/model.onnx");
    props.getRerank().getLocal().setTokenizerPath("classpath:missing/tokenizer.json");

    RerankService service = new RerankService(props);

    var scores = service.scoreAll(
        List.of(TextSegment.from("doc1"), TextSegment.from("doc2")),
        "query").content();

    assertThat(scores).containsExactly(0.0, 0.0);
    assertThat(service.isEnabled()).isFalse();
    assertThat(service.getEffectiveProvider()).isEqualTo("local");
  }
}
