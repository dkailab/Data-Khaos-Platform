package com.datakhaos.workflow.executor;

import com.datakhaos.workflow.entity.WorkflowNode;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 节点执行上下文：由 {@link com.datakhaos.workflow.runner.WorkflowRunner} 在调度节点前组装。
 *
 * <p>{@code content}（sql/script/command）与 {@code datasourceId} 均已通过运行参数模板替换完成，
 * 执行器无需感知 DAG，只负责把这一份上下文跑出结果。</p>
 */
@Data
@Builder
public class NodeContext {

    /** 节点定义（含 nodeCode / nodeName / nodeType / configJson） */
    private WorkflowNode node;

    /** 主执行载荷：SQL 文本 或 Shell/Python 脚本 */
    private String content;

    /** 目标数据源ID（SQL / DATA_OP 节点使用） */
    private String datasourceId;

    /** 解析后的配置项（configJson） */
    private Map<String, Object> config;

    /** 运行时注入参数（已做 ${param} 替换） */
    private Map<String, Object> params;

    /** 超时（秒），0/空 表示使用默认 */
    private Long timeoutSeconds;

    /** 所属工作流运行ID（供 PIPELINE 节点回填关联） */
    private String runId;

    /** 当前节点运行记录ID（供 PIPELINE 节点回填关联） */
    private String nodeRunId;
}