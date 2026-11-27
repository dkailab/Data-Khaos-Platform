package com.datakhaos.workflow.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.workflow.dto.NodeDebugResult;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.executor.ExecResult;
import com.datakhaos.workflow.executor.NodeContext;
import com.datakhaos.workflow.executor.NodeExecutor;
import com.datakhaos.workflow.executor.NodeExecutorFactory;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 单节点调试：同步执行一个节点并返回日志，不创建运行实例、不推进下游。
 *
 * <p>用途是「先把这段 SQL 跑通再上调度」。参数按节点级参数覆盖工作流参数的规则合并，
 * 外部 SQL 文件也按节点当前配置解析（草稿/已发布）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowNodeDebugService {

    private static final int MAX_DEBUG_LOG = 8000;

    private final WorkflowDefMapper defMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowNodeContextFactory contextFactory;
    private final NodeExecutorFactory executorFactory;
    private final WorkflowProjectAccess access;

    public NodeDebugResult debug(String wfId, String nodeCode, Map<String, Object> params) {
        WorkflowDef definition = defMapper.selectById(wfId);
        if (definition == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务流不存在或已删除");
        }
        access.requireAction(definition.getProjectId(), "task:develop");
        WorkflowNode node = nodeMapper.selectOne(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWfId, wfId)
                .eq(WorkflowNode::getNodeCode, nodeCode));
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "节点不存在: " + nodeCode);
        }
        Map<String, Object> runtime = params == null ? Map.of() : params;
        String bizDate = runtime.get("bizDate") == null ? null : String.valueOf(runtime.get("bizDate"));
        long start = System.currentTimeMillis();
        try {
            NodeContext context = contextFactory.build(node, runtime, null, null, bizDate);
            NodeExecutor executor = executorFactory.get(node.getNodeType());
            ExecResult result = executor.execute(context);
            long cost = System.currentTimeMillis() - start;
            String text = result == null ? null : result.getLog();
            return new NodeDebugResult(result != null && result.isSuccess(),
                    result == null || result.getRows() == null ? 0 : result.getRows(),
                    cost, truncate(text, MAX_DEBUG_LOG),
                    result != null && result.isSuccess() ? null : "执行返回失败");
        } catch (Throwable e) {
            log.info("节点调试失败 wfId={}, node={}: {}", wfId, nodeCode, e.getMessage());
            return new NodeDebugResult(false, 0, System.currentTimeMillis() - start,
                    null, truncate(e.getMessage(), 2000));
        }
    }

    private String truncate(String text, int max) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
