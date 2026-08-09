package com.linrun.interview.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.business.entity.JudgeSubmissionEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface JudgeSubmissionMapper extends BaseMapper<JudgeSubmissionEntity> {

  @Update("""
      UPDATE judge_submissions
      SET status = 'QUEUED', provider_submission_id = NULL, failure_code = NULL,
          diagnostic = NULL, passed_count = 0, time_ms = NULL, memory_kb = NULL,
          completed_at = NULL, updated_at = #{updatedAt}, lock_version = lock_version + 1
      WHERE id = #{id} AND user_id = #{userId}
        AND (status IN ('UNAVAILABLE', 'INTERNAL_ERROR')
          OR (status IN ('QUEUED', 'PROCESSING') AND updated_at <= #{staleBefore}))
      """)
  int reserveRejudge(
      @Param("id") Long id,
      @Param("userId") Long userId,
      @Param("staleBefore") LocalDateTime staleBefore,
      @Param("updatedAt") LocalDateTime updatedAt);
}
