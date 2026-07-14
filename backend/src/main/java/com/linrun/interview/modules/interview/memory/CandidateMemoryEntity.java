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
 * 跨会话能力观测条目（candidate_memory 表）。
 * 每个已回答问题形成一条带能力原子、分数与证据 ID 的观测，Planner 按聚合画像使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("candidate_memory")
public class CandidateMemoryEntity {

  public static final String KIND_STRENGTH = "strength";
  public static final String KIND_WEAKNESS = "weakness";
  public static final String KIND_DEVELOPING = "developing";

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long userId;

  /** 面试方向（可空，通用画像） */
  private String skillId;

  /** 稳定能力原子 ID；旧数据可为空并按 topic 兼容聚合 */
  private String capabilityAtomId;

  /** 具体技术主题，如「Redis 持久化」 */
  private String topic;

  /** strength / developing / weakness */
  private String kind;

  /** 来源题号，用于会话内幂等 */
  private Integer questionIndex;

  /** 逐题评估分 0-100 */
  private Integer masteryScore;

  /** 一句话依据（来自评估反馈） */
  private String evidence;

  /** 该题出题时实际选用的 RAG evidence ID（JSON 数组） */
  private String evidenceIdsJson;

  /** 来源面试会话业务 ID */
  private String sessionId;

  private LocalDateTime createdAt;
}
