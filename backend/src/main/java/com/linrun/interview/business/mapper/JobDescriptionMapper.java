package com.linrun.interview.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.business.job.JobDescriptionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobDescriptionMapper extends BaseMapper<JobDescriptionEntity> {
}
