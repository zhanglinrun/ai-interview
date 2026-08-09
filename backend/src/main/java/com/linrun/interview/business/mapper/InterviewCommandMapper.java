package com.linrun.interview.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.business.entity.InterviewCommandEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InterviewCommandMapper extends BaseMapper<InterviewCommandEntity> {

  /** 仅当指令仍为 PROCESSING 时标记失败，避免覆盖已经完成的幂等结果。 */
  @Update("""
      UPDATE interview_commands
      SET status = 'FAILED',
          failure_code = #{failureCode},
          failure_detail = #{failureDetail},
          updated_at = #{failedAt},
          completed_at = #{failedAt}
      WHERE id = #{commandPkId}
        AND user_id = #{userId}
        AND session_id = #{sessionId}
        AND command_id = #{commandId}
        AND expected_session_version = #{expectedVersion}
        AND status = 'PROCESSING'
      """)
  int failProcessingCommand(
      @Param("commandPkId") Long commandPkId,
      @Param("userId") Long userId,
      @Param("sessionId") String sessionId,
      @Param("commandId") String commandId,
      @Param("expectedVersion") Long expectedVersion,
      @Param("failureCode") String failureCode,
      @Param("failureDetail") String failureDetail,
      @Param("failedAt") LocalDateTime failedAt
  );

  /**
   * 过期租约回收专用 CAS。staleBefore 同时约束 updated_at，正常执行中的新鲜指令不会被回收。
   */
  @Update("""
      UPDATE interview_commands
      SET status = 'FAILED',
          failure_code = #{failureCode},
          failure_detail = #{failureDetail},
          updated_at = #{failedAt},
          completed_at = #{failedAt}
      WHERE id = #{commandPkId}
        AND user_id = #{userId}
        AND session_id = #{sessionId}
        AND command_id = #{commandId}
        AND expected_session_version = #{expectedVersion}
        AND status = 'PROCESSING'
        AND updated_at <= #{staleBefore}
      """)
  int failStaleProcessingCommand(
      @Param("commandPkId") Long commandPkId,
      @Param("userId") Long userId,
      @Param("sessionId") String sessionId,
      @Param("commandId") String commandId,
      @Param("expectedVersion") Long expectedVersion,
      @Param("staleBefore") LocalDateTime staleBefore,
      @Param("failureCode") String failureCode,
      @Param("failureDetail") String failureDetail,
      @Param("failedAt") LocalDateTime failedAt
  );
}
