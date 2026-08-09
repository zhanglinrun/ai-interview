package com.linrun.interview.business.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.business.entity.AgentRunStepEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 编排轨迹步骤 Mapper
 */
@Mapper
public interface AgentRunStepMapper extends BaseMapper<AgentRunStepEntity> {
}

