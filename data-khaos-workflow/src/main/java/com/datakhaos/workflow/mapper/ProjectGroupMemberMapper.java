package com.datakhaos.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datakhaos.workflow.entity.ProjectGroupMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目组成员只读 Mapper（用于工作流项目范围校验）。
 */
@Mapper
public interface ProjectGroupMemberMapper extends BaseMapper<ProjectGroupMember> {
}
