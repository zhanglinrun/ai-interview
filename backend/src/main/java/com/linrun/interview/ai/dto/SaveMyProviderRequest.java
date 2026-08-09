package com.linrun.interview.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 保存用户「我的模型」（BYOK）请求。{@code temperature} 可选。
 *
 * <p>{@code apiKey} 不强制非空：已配置的用户仅改 baseUrl/chatModel 时可留空，表示保持已存的
 * Key 密文不变（详见 {@code UserLlmProviderService#saveMine}）；首次配置（尚无记录）时后端会
 * 校验必须填写 Key。故校验交由 Service 层按「是否已有记录」判定，而非 {@code @NotBlank}。
 */
public record SaveMyProviderRequest(
    @NotBlank String baseUrl,
    String apiKey,
    @NotBlank String chatModel,
    Double temperature
) {}
