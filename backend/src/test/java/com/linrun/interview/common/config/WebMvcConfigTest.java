package com.linrun.interview.common.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.linrun.interview.common.security.JwtInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

@DisplayName("Web MVC 配置")
class WebMvcConfigTest {

  @Test
  @DisplayName("SSE 异步请求使用受管且有并发上限的执行器")
  void shouldConfigureManagedAsyncExecutor() {
    JwtInterceptor jwtInterceptor = mock(JwtInterceptor.class);
    AsyncTaskExecutor questionExecutor = mock(AsyncTaskExecutor.class);
    AsyncSupportConfigurer configurer = mock(AsyncSupportConfigurer.class);
    WebMvcConfig webMvcConfig = new WebMvcConfig(jwtInterceptor, questionExecutor);

    webMvcConfig.configureAsyncSupport(configurer);

    verify(configurer).setTaskExecutor(questionExecutor);
  }
}
