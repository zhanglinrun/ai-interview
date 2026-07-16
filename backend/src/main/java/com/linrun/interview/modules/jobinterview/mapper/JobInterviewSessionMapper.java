package com.linrun.interview.modules.jobinterview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.modules.jobinterview.model.JobInterviewSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JobInterviewSessionMapper extends BaseMapper<JobInterviewSessionEntity> {

  /**
   * 用会话版本与空闲指令槽同时抢占执行权。版本不匹配或已有指令时返回 0。
   */
  @Update("""
      UPDATE interview_sessions
      SET active_command_id = #{commandId}
      WHERE id = #{sessionPkId}
        AND user_id = #{userId}
        AND session_version = #{expectedVersion}
        AND active_command_id IS NULL
        AND preparation_run_id IS NOT NULL
      """)
  int claimCommand(
      @Param("sessionPkId") Long sessionPkId,
      @Param("userId") Long userId,
      @Param("expectedVersion") Long expectedVersion,
      @Param("commandId") String commandId
  );

  /**
   * 释放仍属于指定会话版本和指令的执行槽。用于正常失败和过期租约回收；若指令已经完成、
   * 会话版本已推进或其他指令已抢占，CAS 返回 0，避免误释放并发中的新指令。
   */
  @Update("""
      UPDATE interview_sessions
      SET active_command_id = NULL
      WHERE id = #{sessionPkId}
        AND user_id = #{userId}
        AND session_version = #{expectedVersion}
        AND active_command_id = #{commandId}
        AND preparation_run_id IS NOT NULL
      """)
  int releaseCommand(
      @Param("sessionPkId") Long sessionPkId,
      @Param("userId") Long userId,
      @Param("expectedVersion") Long expectedVersion,
      @Param("commandId") String commandId
  );
}
