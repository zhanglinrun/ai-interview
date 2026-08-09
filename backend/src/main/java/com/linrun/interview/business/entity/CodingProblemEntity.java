package com.linrun.interview.business.entity;

import com.linrun.interview.business.constant.ProblemDifficulty;
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
@TableName("coding_problems")
public class CodingProblemEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String catalogVersion;
  private Integer hotRank;
  private String platform;
  private String platformProblemId;
  private String slug;
  private String title;
  private ProblemDifficulty difficulty;
  private String tagsJson;
  private String sourceUrl;
  private Boolean active;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
