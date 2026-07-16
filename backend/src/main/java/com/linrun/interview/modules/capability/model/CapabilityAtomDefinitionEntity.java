package com.linrun.interview.modules.capability.model;

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
@TableName("capability_atom_definitions")
public class CapabilityAtomDefinitionEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String atomId;
  private String version;
  private String name;
  private String description;
  private String capabilityDomain;
  private String jobTracksJson;
  private String parentAtomId;
  private String contentHash;
  private LocalDateTime createdAt;
}
