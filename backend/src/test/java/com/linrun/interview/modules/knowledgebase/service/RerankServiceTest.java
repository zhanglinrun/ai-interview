package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.common.config.LlmProviderProperties;
import com.linrun.interview.modules.knowledgebase.rag.LocalOnnxRerankModel;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RerankService 降级测试")
class RerankServiceTest {

  @Test
  @DisplayName("本地模型不可用且云端无 Key 时应返回等分")
  void returnsZeroScoresWhenUnavailable() {
    KnowledgeBaseQueryProperties props = new KnowledgeBaseQueryProperties();
    props.getRerank().setEnabled(true);
    props.getRerank().setProvider("local");
    props.getRerank().getLocal().setModelPath("classpath:missing/model.onnx");
    props.getRerank().getLocal().setTokenizerPath("classpath:missing/tokenizer.json");

    LlmProviderProperties llmProps = new LlmProviderProperties();
    RerankService service = new RerankService(props, llmProps);

    var scores = service.scoreAll(
        List.of(TextSegment.from("doc1"), TextSegment.from("doc2")),
        "query").content();

    assertThat(scores).containsExactly(0.0, 0.0);
    assertThat(service.isEnabled()).isFalse();
  }

  @Test
  @DisplayName("显式 cloud provider 且无 API Key 时不应启用")
  void cloudWithoutKeyDisabled() {
    KnowledgeBaseQueryProperties props = new KnowledgeBaseQueryProperties();
    props.getRerank().setEnabled(true);
    props.getRerank().setProvider("cloud");

    RerankService service = new RerankService(props, new LlmProviderProperties());
    assertThat(service.isEnabled()).isFalse();
  }
}
