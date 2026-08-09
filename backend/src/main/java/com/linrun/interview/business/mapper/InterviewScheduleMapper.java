package com.linrun.interview.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.business.entity.InterviewScheduleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InterviewScheduleMapper extends BaseMapper<InterviewScheduleEntity> {

  @Update("""
      UPDATE interview_schedule SET status = #{newStatus}
      WHERE status = #{oldStatus} AND interview_time < #{before}
      """)
  int updateStatusByStatusAndInterviewTimeBefore(
      @Param("newStatus") String newStatus,
      @Param("oldStatus") String oldStatus,
      @Param("before") java.time.LocalDateTime before);
}
