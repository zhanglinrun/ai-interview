package com.linrun.interview.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.business.entity.CodingAttemptEntity;
import com.linrun.interview.business.constant.CodingAttemptStatus;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CodingAttemptMapper extends BaseMapper<CodingAttemptEntity> {

  @Update("""
      UPDATE coding_attempts
      SET status = #{status}, completed_at = #{completedAt}, updated_at = #{updatedAt},
          lock_version = lock_version + 1
      WHERE id = #{id} AND user_id = #{userId} AND status != 'ABORTED'
      """)
  int updateAfterHiddenJudge(
      @Param("id") Long id,
      @Param("userId") Long userId,
      @Param("status") CodingAttemptStatus status,
      @Param("completedAt") LocalDateTime completedAt,
      @Param("updatedAt") LocalDateTime updatedAt);
}
