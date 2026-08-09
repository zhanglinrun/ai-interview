package com.linrun.interview.business.entity;

import com.linrun.interview.business.constant.CodingLanguage;
import com.baomidou.mybatisplus.annotation.IdType;
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
@TableName("coding_drafts")
public class CodingDraftEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private Long attemptId;
  private CodingLanguage language;
  private String sourceCode;
  private String codeHash;
  private Integer revision;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
