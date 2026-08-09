package com.linrun.interview.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("llm_provider_config")
public class LlmProviderEntity {

  @TableId(type = IdType.INPUT)
  private String id;

  private String baseUrl;

  private String apiKeyCiphertext;

  private String apiKeyNonce;

  private String model;

  private String embeddingModel;

  private Integer embeddingDimensions;

  private boolean supportsEmbedding;

  private Double temperature;

  private boolean enabled;

  private boolean builtin;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;


}
