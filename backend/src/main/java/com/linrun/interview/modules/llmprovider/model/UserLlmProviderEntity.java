package com.linrun.interview.modules.llmprovider.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户级 LLM Provider（BYOK）：每个用户一条「我的模型」，仅 Chat/LLM 走 per-user 解析，
 * Embedding 仍走全局默认 Provider。API Key 以 AES-GCM 密文 + nonce 存储，明文永不落库/回显。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_llm_provider")
public class UserLlmProviderEntity {

  @TableId(type = IdType.INPUT)
  private Long userId;

  private String baseUrl;

  private String apiKeyCiphertext;

  private String apiKeyNonce;

  private String chatModel;

  private Double temperature;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
