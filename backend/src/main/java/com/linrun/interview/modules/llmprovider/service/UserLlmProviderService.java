package com.linrun.interview.modules.llmprovider.service;

import com.linrun.interview.common.ai.ApiPathResolver;
import com.linrun.interview.common.ai.LlmProviderRegistry;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.llmprovider.dto.MyProviderDTO;
import com.linrun.interview.modules.llmprovider.dto.ProviderTestResult;
import com.linrun.interview.modules.llmprovider.dto.SaveMyProviderRequest;
import com.linrun.interview.modules.llmprovider.mapper.UserLlmProviderMapper;
import com.linrun.interview.modules.llmprovider.model.UserLlmProviderEntity;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 用户级 LLM Provider（BYOK）读/存/删/连通性测试。
 *
 * <p>每个用户一条「我的模型」（baseUrl + apiKey + chatModel[+temperature]），API Key 经
 * {@link ApiKeyEncryptionService} AES-GCM 加密后落库，明文永不回显/落日志。写操作后调用
 * {@link LlmProviderRegistry#evictUser(Long)} 清缓存，使变更即时生效。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserLlmProviderService {

  private final UserLlmProviderMapper userLlmProviderMapper;
  private final ApiKeyEncryptionService encryptionService;
  private final LlmProviderRegistry registry;

  public MyProviderDTO getMine(Long userId) {
    UserLlmProviderEntity entity = userId == null ? null : userLlmProviderMapper.selectById(userId);
    if (entity == null) {
      return MyProviderDTO.notConfigured();
    }
    String maskedApiKey = maskApiKey(
        encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()));
    return new MyProviderDTO(
        true,
        entity.getBaseUrl(),
        entity.getChatModel(),
        entity.getTemperature(),
        maskedApiKey);
  }

  @Transactional
  public void saveMine(Long userId, SaveMyProviderRequest request) {
    Long resolvedUserId = requireUserId(userId);
    String baseUrl = requireNonBlank(request.baseUrl(), "baseUrl");
    String chatModel = requireNonBlank(request.chatModel(), "chatModel");
    // apiKey 留空表示「保持已存 Key 不变」（改模型名不必重填 Key）；首次配置时下方强制要求填写。
    String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
    boolean keyProvided = !apiKey.isEmpty();
    LocalDateTime now = LocalDateTime.now();

    UserLlmProviderEntity existing = userLlmProviderMapper.selectById(resolvedUserId);
    if (existing == null) {
      if (!keyProvided) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "首次配置「我的模型」必须填写访问凭证");
      }
      ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
      userLlmProviderMapper.insert(UserLlmProviderEntity.builder()
          .userId(resolvedUserId)
          .baseUrl(baseUrl)
          .apiKeyCiphertext(encrypted.ciphertext())
          .apiKeyNonce(encrypted.nonce())
          .chatModel(chatModel)
          .temperature(request.temperature())
          .createdAt(now)
          .updatedAt(now)
          .build());
    } else {
      existing.setBaseUrl(baseUrl);
      existing.setChatModel(chatModel);
      existing.setTemperature(request.temperature());
      existing.setUpdatedAt(now);
      // 仅当传入新 Key 时才覆盖密文/nonce；留空则复用原密文，避免误清空已配置的 Key。
      if (keyProvided) {
        ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
        existing.setApiKeyCiphertext(encrypted.ciphertext());
        existing.setApiKeyNonce(encrypted.nonce());
      }
      userLlmProviderMapper.updateById(existing);
    }
    registry.evictUser(resolvedUserId);
    log.info("Saved user LLM provider: userId={}, baseUrl={}, model={}, keyUpdated={}",
        resolvedUserId, baseUrl, chatModel, keyProvided);
  }

  @Transactional
  public void deleteMine(Long userId) {
    Long resolvedUserId = requireUserId(userId);
    userLlmProviderMapper.deleteById(resolvedUserId);
    registry.evictUser(resolvedUserId);
    log.info("Deleted user LLM provider: userId={}", resolvedUserId);
  }

  /**
   * 用用户自己的「我的模型」做一次最小 chat 连通性测试。含外部 API 调用，禁止 {@code @Transactional}。
   */
  public ProviderTestResult testMine(Long userId) {
    UserLlmProviderEntity entity = requireEntity(userId);
    String apiKey = encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext());
    return doTestProvider(entity.getBaseUrl(), apiKey, entity.getChatModel(), userId);
  }

  private Long requireUserId(Long userId) {
    if (userId == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或 token 无效");
    }
    return userId;
  }

  private UserLlmProviderEntity requireEntity(Long userId) {
    UserLlmProviderEntity entity = userId == null ? null : userLlmProviderMapper.selectById(userId);
    if (entity == null) {
      throw new BusinessException(ErrorCode.USER_LLM_NOT_CONFIGURED,
          ErrorCode.USER_LLM_NOT_CONFIGURED.getMessage());
    }
    return entity;
  }

  private String requireNonBlank(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 不能为空");
    }
    return value.trim();
  }

  private String maskApiKey(String apiKey) {
    if (apiKey == null || apiKey.length() <= 4) {
      return "****";
    }
    return "****" + apiKey.substring(apiKey.length() - 4);
  }

  private ProviderTestResult doTestProvider(String baseUrl, String apiKey, String model, Long userId) {
    try {
      SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
      requestFactory.setConnectTimeout(5000);
      requestFactory.setReadTimeout(10000);

      RestClient restClient = RestClient.builder()
          .defaultHeader("Authorization", "Bearer " + apiKey)
          .requestFactory(requestFactory)
          .build();

      Map<String, Object> requestBody = buildConnectivityTestRequestBody(model);
      List<String> candidateUrls = buildConnectivityTestUrls(baseUrl);
      String lastFailureMessage = "Unknown error";

      for (String targetUrl : candidateUrls) {
        try {
          restClient.post()
              .uri(URI.create(targetUrl))
              .body(requestBody)
              .retrieve()
              .toEntity(String.class);
          log.info("User provider connectivity test succeeded: userId={}, baseUrl={}, targetUrl={}, model={}",
              userId, baseUrl, targetUrl, model);
          return ProviderTestResult.builder()
              .success(true)
              .message("连接成功")
              .model(model)
              .build();
        } catch (RestClientResponseException e) {
          String responseBody = abbreviate(e.getResponseBodyAsString());
          lastFailureMessage = String.format(
              "HTTP %s on %s, body=%s", e.getStatusCode().value(), targetUrl, responseBody);
          log.warn(
              "User provider connectivity test failed with response: userId={}, baseUrl={}, targetUrl={}, model={}, status={}, body={}",
              userId,
              baseUrl,
              targetUrl,
              model,
              e.getStatusCode().value(),
              responseBody,
              e);
        } catch (Exception e) {
          lastFailureMessage = String.format(
              "%s on %s: %s", e.getClass().getSimpleName(), targetUrl, e.getMessage());
          log.warn(
              "User provider connectivity test failed: userId={}, baseUrl={}, targetUrl={}, model={}, error={}",
              userId,
              baseUrl,
              targetUrl,
              model,
              e.getMessage(),
              e);
        }
      }
      return ProviderTestResult.builder()
          .success(false)
          .message("连接失败: " + lastFailureMessage)
          .model(model)
          .build();
    } catch (Exception e) {
      log.warn("User provider connectivity test setup failed: userId={}, baseUrl={}, model={}, error={}",
          userId, baseUrl, model, e.getMessage(), e);
      return ProviderTestResult.builder()
          .success(false)
          .message("连接失败: " + e.getMessage())
          .model(model)
          .build();
    }
  }

  private List<String> buildConnectivityTestUrls(String baseUrl) {
    String normalizedBaseUrl = ApiPathResolver.stripTrailingSlashes(baseUrl);
    LinkedHashSet<String> candidateUrls = new LinkedHashSet<>();
    candidateUrls.add(normalizedBaseUrl + "/chat/completions");
    if (!ApiPathResolver.baseUrlContainsVersion(normalizedBaseUrl)) {
      candidateUrls.add(normalizedBaseUrl + "/v1/chat/completions");
    }
    return List.copyOf(candidateUrls);
  }

  private Map<String, Object> buildConnectivityTestRequestBody(String model) {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("model", model);
    requestBody.put("messages", List.of(Map.of("role", "user", "content", "Reply with OK only.")));
    requestBody.put("max_tokens", 1);
    return requestBody;
  }

  private String abbreviate(String text) {
    if (text == null || text.isBlank()) {
      return "[no body]";
    }
    String normalized = text.replaceAll("\\s+", " ").trim();
    if (normalized.length() <= 200) {
      return normalized;
    }
    return normalized.substring(0, 200) + "...";
  }
}
