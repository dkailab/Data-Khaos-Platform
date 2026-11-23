package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作流定义（DAG 图的元信息 + 调度配置）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_def")
public class WorkflowDef extends WorkflowBaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 工作流名称 */
    private String name;

    /** 工作流编码 */
    private String code;

    /** Cron 表达式（调度触发；为空则仅手动触发） */
    private String cronExpression;

    /** 0:禁用/草稿 1:启用 2:发布 */
    private Integer status;

    /** 描述 */
    private String description;

    /** 负责人 */
    private String owner;

    /** 所属项目ID（空表示不校验项目范围，兼容旧数据） */
    private String projectId;

    /** 运行参数模板（JSON，供节点 ${param} 替换） */
    private String params;
}