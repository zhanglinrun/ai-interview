package com.linrun.interview.business.entity;

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
@TableName("coding_problem_versions")
public class CodingProblemVersionEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long problemId;
  private String version;
  private String statementText;
  private String constraintsJson;
  private String publicExamplesJson;
  private String complexityRubricJson;
  private String languageSpecsJson;
  private String publicTestsJson;
  private String hiddenTestsJson;
  private String contentHash;
  private Boolean enabled;
  private Boolean javaEnabled;
  private Boolean pythonEnabled;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
