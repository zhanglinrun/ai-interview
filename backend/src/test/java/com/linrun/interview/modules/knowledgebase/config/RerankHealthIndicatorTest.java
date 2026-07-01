package com.linrun.interview.modules.knowledgebase.config;

import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import com.linrun.interview.modules.knowledgebase.service.RerankService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Rerank 健康检查测试")
class RerankHealthIndicatorTest {

  @Test
  @DisplayName("rerank 关闭时应为 UP")
  void disabledIsUp() {
    KnowledgeBaseQueryProperties props = new KnowledgeBaseQueryProperties();
    props.getRerank().setEnabled(false);
    RerankService rerankService = mock(RerankService.class);
    RerankHealthIndicator indicator = new RerankHealthIndicator(props, rerankService);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("两路均不可用时应为 DOWN")
  void unavailableIsDown() {
    KnowledgeBaseQueryProperties props = new KnowledgeBaseQueryProperties();
    props.getRerank().setEnabled(true);
    RerankService rerankService = mock(RerankService.class);
    when(rerankService.isEnabled()).thenReturn(false);
    RerankHealthIndicator indicator = new RerankHealthIndicator(props, rerankService);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }
}
