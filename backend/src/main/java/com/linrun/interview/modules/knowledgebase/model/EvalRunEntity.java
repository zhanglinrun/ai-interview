package com.linrun.interview.modules.knowledgebase.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("eval_runs")
public class EvalRunEntity {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long userId;

  private String runId;
  private String title;
  private String baselineKey;
  private Boolean baseline;

  private String requestJson;
  private String responseJson;

  private Integer intentTotal;
  private Integer intentCorrect;
  private Double intentAccuracy;
  private Double intentMacroF1;

  private String ragRunId;
  private Double ragHitRate;
  private Double ragMrr;
  private Double ragNdcg;

  private Integer judgeTotal;
  private Integer judgePassed;
  private Double judgePassRate;
  private Double judgeAverageOverall;
  private Double judgeAverageRelevance;
  private Double judgeAverageAccuracy;
  private Double judgeAverageCompleteness;
  private Double judgeAverageHelpfulness;

  private Double overallScore;
  private Boolean regression;
  private Double regressionThreshold;
  private LocalDateTime createdAt;
}
