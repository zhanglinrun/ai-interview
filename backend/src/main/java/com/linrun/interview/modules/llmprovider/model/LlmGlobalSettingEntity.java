package com.linrun.interview.modules.llmprovider.model;

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
@TableName("llm_global_setting")
public class LlmGlobalSettingEntity {

  public static final Long SINGLETON_ID = 1L;

  @TableId(type = IdType.AUTO)
  private Long id;

  private String defaultChatProviderId;

  private String defaultEmbeddingProviderId;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;


}
