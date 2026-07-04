package com.linrun.interview.modules.interview.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 跨会话候选人画像记忆条目（candidate_memory 表）。
 * 评估完成后由 LLM 从评估报告抽取，Planner 出大纲时按 userId+skillId 注入。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("candidate_memory")
public class CandidateMemoryEntity {

  public static final String KIND_STRENGTH = "strength";
  public static final String KIND_WEAKNESS = "weakness";

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long userId;

  /** 面试方向（可空，通用画像） */
  private String skillId;

  /** 具体技术主题，如「Redis 持久化」 */
  private String topic;

  /** strength / weakness */
  private String kind;

  /** 一句话依据（来自评估反馈） */
  private String evidence;

  /** 来源面试会话业务 ID */
  private String sessionId;

  private LocalDateTime createdAt;
}
