package com.datakhaos.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datakhaos.workflow.entity.WorkflowBizdateLock;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowBizdateLockMapper extends BaseMapper<WorkflowBizdateLock> {
}
