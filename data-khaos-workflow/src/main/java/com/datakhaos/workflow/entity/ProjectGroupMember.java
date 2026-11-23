package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 项目组成员（只读映射 sg_project_group_member，用于工作流项目范围校验）。
 */
@Data
@TableName("sg_project_group_member")
public class ProjectGroupMember implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String id;

    private String projectGroupId;

    private String userId;

    private String projectRoleId;

    private Integer isPrimary;

    private LocalDateTime createTime;
}
