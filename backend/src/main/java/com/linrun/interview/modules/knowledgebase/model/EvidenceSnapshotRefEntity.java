package com.linrun.interview.modules.knowledgebase.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linrun.interview.common.evidence.DataDomain;
import lombok.Data;

@Data
@TableName("evidence_snapshot_refs")
public class EvidenceSnapshotRefEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private String snapshotId;
  private DataDomain dataDomain;
  private String resourceId;
  private String resourceVersion;
  private String evidenceId;
}
