package com.linrun.interview.ai.dto;

/**
 * 用户「我的模型」回显（BYOK）。明文 API Key 永不返回，仅回传脱敏提示 {@code maskedApiKey}。
 * 未配置时 {@code configured=false}，其余字段为 {@code null}。
 */
public record MyProviderDTO(
    boolean configured,
    String baseUrl,
    String chatModel,
    Double temperature,
    String maskedApiKey
) {

  public static MyProviderDTO notConfigured() {
    return new MyProviderDTO(false, null, null, null, null);
  }
}
