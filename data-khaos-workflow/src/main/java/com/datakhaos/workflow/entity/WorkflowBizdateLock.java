package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作流业务日活跃运行锁：同一 (wf_id, biz_date) 仅允许一条活跃运行，运行终态时释放。
 */
@Data
@TableName("workflow_bizdate_lock")
public class WorkflowBizdateLock implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String wfId;

    /** 业务日期 YYYY-MM-DD */
    private String bizDate;

    /** 占用该业务日的运行ID */
    private String runId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
