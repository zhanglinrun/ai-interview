package com.linrun.interview.rag.config;

import com.linrun.interview.rag.service.RerankService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RerankStartupValidator fail-fast")
class RerankStartupValidatorTest {

  @Test
  @DisplayName("fail-fast 开启且模型缺失时应阻断启动")
  void failFastWhenModelMissing() {
    KnowledgeBaseQueryProperties props = new KnowledgeBaseQueryProperties();
    props.getRerank().setEnabled(true);
    props.getRerank().setFailFastOnMissingModel(true);
    props.getRerank().getLocal().setModelPath("classpath:missing/model.onnx");
    props.getRerank().getLocal().setTokenizerPath("classpath:missing/tokenizer.json");

    RerankService rerankService = mock(RerankService.class);
    when(rerankService.isEnabled()).thenReturn(false);

    RerankStartupValidator validator = new RerankStartupValidator(
        props, rerankService, new DefaultResourceLoader());

    assertThatThrownBy(() -> validator.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("本地 ONNX rerank 模型未就绪");
  }
}
