package com.linrun.interview.modules.knowledgebase.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linrun.interview.common.evidence.EvidenceStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("evidence_snapshots")
public class EvidenceSnapshotEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private String snapshotId;
  private String contextType;
  private String contextId;
  private String capabilityAtomKey;
  private String queryText;
  private EvidenceStatus evidenceStatus;
  private String packetJson;
  private Boolean sourceAvailable;
  private LocalDateTime createdAt;
}
