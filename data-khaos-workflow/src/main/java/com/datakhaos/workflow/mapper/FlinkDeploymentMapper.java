package com.datakhaos.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datakhaos.workflow.entity.FlinkDeployment;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FlinkDeploymentMapper extends BaseMapper<FlinkDeployment> {
}
