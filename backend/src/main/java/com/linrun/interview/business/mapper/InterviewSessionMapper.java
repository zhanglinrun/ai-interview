package com.linrun.interview.business.mapper;

import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSessionEntity> {

  @Update("""
      UPDATE interview_sessions
      SET current_question_id = NULL
      WHERE user_id = #{userId} AND id = #{sessionPkId}
      """)
  int clearCurrentQuestionId(@Param("userId") Long userId, @Param("sessionPkId") Long sessionPkId);

  @Delete("""
      DELETE FROM interview_evidence_reports
      WHERE user_id = #{userId} AND session_id = #{sessionPkId}
      """)
  int deleteEvidenceReportsBySession(@Param("userId") Long userId, @Param("sessionPkId") Long sessionPkId);

  @Delete("""
      DELETE FROM interview_questions
      WHERE user_id = #{userId} AND session_id = #{sessionPkId}
      """)
  int deleteQuestionsBySession(@Param("userId") Long userId, @Param("sessionPkId") Long sessionPkId);
}
