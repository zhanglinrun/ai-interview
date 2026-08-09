package com.linrun.interview.ai.service;

/**
 * LLM Provider 的 OpenAI 兼容端点 baseUrl 适配。
 *
 * <p>LangChain4j 的 {@code OpenAiChatModel}/{@code OpenAiEmbeddingModel} 直接接收 baseUrl，
 * 内部按 OpenAI 约定拼接相对路径（{@code /chat/completions}、{@code /embeddings}），
 * 因此只需把各 Provider 的 baseUrl 规整为不含尾斜杠的完整 v1 端点即可。
 *
 * <p>DashScope 等兼容端点 baseUrl 形如 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}，
 * 直接传入即可拼出 {@code .../compatible-mode/v1/chat/completions}，无需额外路径配置。
 */
public final class ApiPathResolver {

  private static final java.util.regex.Pattern TRAILING_VERSION =
      java.util.regex.Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

  private ApiPathResolver() {}

  /**
   * 规整 baseUrl：去尾斜杠。LangChain4j 会自行拼接 OpenAI 相对路径。
   */
  public static String resolveBaseUrl(String baseUrl) {
    return stripTrailingSlashes(baseUrl);
  }

  public static boolean baseUrlContainsVersion(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    String stripped = stripTrailingSlashes(baseUrl.trim());
    return TRAILING_VERSION.matcher(stripped).find();
  }

  public static String stripTrailingSlashes(String value) {
    if (value == null) {
      return "";
    }
    String result = value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
