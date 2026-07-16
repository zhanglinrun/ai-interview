package com.linrun.interview.modules.algorithm.config;

import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.algorithm.judge0")
public record Judge0Properties(
    boolean enabled,
    String baseUrl,
    String apiKey,
    String apiHost,
    String apiKeyHeader,
    Integer java21LanguageId,
    Integer python3LanguageId,
    int connectTimeoutMs,
    int requestTimeoutMs,
    int pollIntervalMs,
    int timeoutSeconds,
    double cpuTimeLimitSeconds,
    long memoryLimitKb
) {
  public Judge0Properties {
    apiKeyHeader = blank(apiKeyHeader) ? "X-RapidAPI-Key" : apiKeyHeader.trim();
    connectTimeoutMs = positive(connectTimeoutMs, 5000);
    requestTimeoutMs = positive(requestTimeoutMs, 10000);
    pollIntervalMs = positive(pollIntervalMs, 1000);
    timeoutSeconds = positive(timeoutSeconds, 30);
    cpuTimeLimitSeconds = cpuTimeLimitSeconds > 0 ? cpuTimeLimitSeconds : 3.0;
    memoryLimitKb = memoryLimitKb > 0 ? memoryLimitKb : 262144;
  }

  public boolean availableFor(CodingLanguage language) {
    return enabled && !blank(baseUrl) && languageId(language) != null && languageId(language) > 0;
  }

  public Integer languageId(CodingLanguage language) {
    return language == CodingLanguage.JAVA21 ? java21LanguageId : python3LanguageId;
  }

  private static int positive(int value, int defaultValue) {
    return value > 0 ? value : defaultValue;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
