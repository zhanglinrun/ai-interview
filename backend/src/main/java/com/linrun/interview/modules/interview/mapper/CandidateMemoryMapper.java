package com.linrun.interview.modules.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.modules.interview.memory.CandidateMemoryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 候选人画像记忆 Mapper
 */
@Mapper
public interface CandidateMemoryMapper extends BaseMapper<CandidateMemoryEntity> {
}
