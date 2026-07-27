package com.linrun.interview.modules.resume.service;

import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.ai.StructuredOutputInvoker;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("简历 AI 评分服务")
class ResumeGradingServiceTest {

  @Test
  @DisplayName("模型不可用时应抛出业务异常而不是保存零分占位结果")
  void shouldPropagateModelFailureInsteadOfReturningZeroScore() throws Exception {
    LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
    StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
    ResumeAnalysisProperties properties = new ResumeAnalysisProperties();
    ResumeGradingService service = new ResumeGradingService(
        registry,
        invoker,
        properties,
        new DefaultResourceLoader()
    );
    BusinessException missingByok = new BusinessException(
        ErrorCode.USER_LLM_NOT_CONFIGURED,
        "尚未配置你的模型访问凭证，请先在设置中配置"
    );
    when(registry.getUserChatModel(3L)).thenThrow(missingByok);

    assertThatThrownBy(() -> service.analyzeResume("Java 后端简历", 3L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("尚未配置你的模型访问凭证")
        .extracting("code")
        .isEqualTo(ErrorCode.RESUME_ANALYSIS_FAILED.getCode());
  }
}
