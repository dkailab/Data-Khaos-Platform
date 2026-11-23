package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datakhaos.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_revision")
public class WorkflowRevision extends BaseEntity {
    private String wfId;
    private String projectId;
    private Integer revisionNumber;
    private String note;
    private String createdBy;
    private Integer nodeCount;
    private Integer edgeCount;

    @TableField(select = false)
    private String graphJson;
}
