package com.linrun.interview.business.entity;

import com.linrun.interview.business.constant.CapabilityEvidenceSource;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linrun.interview.rag.model.EvidenceStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("capability_evidence_history")
public class CapabilityEvidenceEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String evidenceRecordId;
  private Long userId;
  private String reportId;
  private Long sessionId;
  private Long questionId;
  private String trainingTaskId;
  private String capabilityAtomId;
  private CapabilityEvidenceSource sourceType;
  private String difficulty;
  private Integer technicalScore;
  private Integer completenessScore;
  private Boolean objectivePassed;
  private BigDecimal confidence;
  private EvidenceStatus evidenceStatus;
  private String evidenceRefsJson;
  private String observation;
  private Boolean eligibleForPromotion;
  private Boolean hintUsed;
  private Boolean answerViewed;
  private Integer redoCount;
  private LocalDateTime occurredAt;
  private LocalDateTime createdAt;
}
