package com.linrun.interview.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.business.service.CandidateMemoryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 候选人画像记忆 Mapper
 */
@Mapper
public interface CandidateMemoryMapper extends BaseMapper<CandidateMemoryEntity> {
}
