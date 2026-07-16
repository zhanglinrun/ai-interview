package com.linrun.interview.modules.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.modules.report.model.InterviewReportEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InterviewReportMapper extends BaseMapper<InterviewReportEntity> {

  @Update("""
      UPDATE interview_evidence_reports
      SET generation_claimed_at = NOW(6),
          generation_attempt = generation_attempt + 1,
          updated_at = NOW(6),
          failure_code = NULL,
          failure_detail = NULL
      WHERE report_id = #{reportId}
        AND user_id = #{userId}
        AND status = 'GENERATING'
        AND (generation_claimed_at IS NULL
             OR generation_claimed_at < #{expiredBefore})
      """)
  int claimGeneration(
      @Param("reportId") String reportId,
      @Param("userId") Long userId,
      @Param("expiredBefore") LocalDateTime expiredBefore
  );

  @Update("""
      UPDATE interview_evidence_reports
      SET generation_claimed_at = NULL,
          updated_at = NOW(6)
      WHERE report_id = #{reportId}
        AND user_id = #{userId}
        AND status = 'GENERATING'
        AND generation_claimed_at IS NOT NULL
        AND generation_claimed_at < #{expiredBefore}
      """)
  int releaseExpiredGenerationClaim(
      @Param("reportId") String reportId,
      @Param("userId") Long userId,
      @Param("expiredBefore") LocalDateTime expiredBefore
  );

  @Update("""
      UPDATE interview_evidence_reports
      SET updated_at = NOW(6)
      WHERE report_id = #{reportId}
        AND user_id = #{userId}
        AND status = 'GENERATING'
        AND generation_claimed_at IS NULL
        AND updated_at < #{dispatchBefore}
      """)
  int reserveUnclaimedGenerationDispatch(
      @Param("reportId") String reportId,
      @Param("userId") Long userId,
      @Param("dispatchBefore") LocalDateTime dispatchBefore
  );
}
