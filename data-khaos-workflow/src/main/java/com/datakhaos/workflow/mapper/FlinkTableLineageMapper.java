package com.datakhaos.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datakhaos.workflow.entity.FlinkTableLineage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FlinkTableLineageMapper extends BaseMapper<FlinkTableLineage> {
}
