package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 跨任务流上游依赖：当前任务流运行前，可按策略校验上游是否已成功。
 *
 * <p>依赖策略：
 * <ul>
 *   <li>{@code STRICT}：同业务日必须成功（有 bizDate 时按 bizDate，无 bizDate 时要求当日成功）；</li>
 *   <li>{@code LOOSE}：最近一次成功即可，不限制业务日；</li>
 *   <li>{@code OFF}：只做依赖图展示，不做运行校验。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_upstream")
public class WorkflowUpstream extends WorkflowBaseEntity {

    public static final String STRATEGY_STRICT = "STRICT";
    public static final String STRATEGY_LOOSE = "LOOSE";
    public static final String STRATEGY_OFF = "OFF";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 下游任务流ID（当前任务流） */
    private String wfId;

    /** 上游任务流ID */
    private String upstreamWfId;

    /** 依赖策略 STRICT / LOOSE / OFF */
    private String dependStrategy;

    /** 参数映射 JSON：{"本地参数":"上游参数"} */
    private String paramMapping;

    /** 是否启用 0:否 1:是 */
    private Integer enabled;
}
