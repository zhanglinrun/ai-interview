package com.linrun.interview.document.job;

import com.linrun.interview.document.config.MineruProperties;
import com.linrun.interview.document.service.impl.DocumentParseTaskService;
import com.linrun.interview.document.service.impl.MineruProcessServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("MinerU 补偿任务条件装配")
class MineruParseCompensationJobConditionTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(TestConfiguration.class);

  @Test
  @DisplayName("MinerU 关闭时即使开启补偿也不注册调度任务")
  void shouldNotRegisterWhenMineruIsDisabled() {
    contextRunner
        .withPropertyValues(
            "file.parse.mineru.enabled=false",
            "file.parse.mineru.compensation-enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(MineruParseCompensationJob.class);
        });
  }

  @Test
  @DisplayName("MinerU 与补偿同时开启时注册调度任务")
  void shouldRegisterWhenBothSwitchesAreEnabled() {
    contextRunner
        .withPropertyValues(
            "file.parse.mineru.enabled=true",
            "file.parse.mineru.compensation-enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(MineruParseCompensationJob.class);
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(MineruProperties.class)
  @Import(MineruParseCompensationJob.class)
  static class TestConfiguration {

    @Bean
    DocumentParseTaskService documentParseTaskService() {
      return mock(DocumentParseTaskService.class);
    }

    @Bean
    MineruProcessServiceImpl mineruProcessService() {
      return mock(MineruProcessServiceImpl.class);
    }
  }
}
