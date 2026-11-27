package com.datakhaos.workflow.executor;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.dto.RunAccept;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowRun;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import com.datakhaos.workflow.mapper.WorkflowRunMapper;
import com.datakhaos.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 嵌套任务流节点：触发另一个已启用任务流，并阻塞等待其到达终态。
 *
 * <p>节点配置（configJson）：{@code {"workflowId": "..."}}。
 * 超时取节点配置的 timeout（秒），默认 3600 秒。子任务流继承当前运行参数（含业务日期），
 * 并通过 {@code __nestedDepth} 限制嵌套层数，避免 A→B→A 的运行时递归。</p>
 *
 * <p>使用 {@link ObjectProvider} 延迟获取 WorkflowService，打断
 * WorkflowService → runner → executorFactory → nestedExecutor → WorkflowService 的构造环。</p>
 *
 * <p>子任务流触发使用「父运行ID + 节点运行ID」作为幂等键：节点重试时返回同一个子运行，
 * 不会重复触发子任务流。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NestedNodeExecutor implements NodeExecutor {

    /** 节点配置键：子任务流ID */
    public static final String CONFIG_WORKFLOW_ID = "workflowId";

    /** 运行参数键：嵌套深度 */
    public static final String PARAM_NESTED_DEPTH = "__nestedDepth";

    private static final int MAX_NESTED_DEPTH = 5;
    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;
    private static final long POLL_INTERVAL_MS = 2000L;
    private static final Set<String> TERMINAL = Set.of("SUCCESS", "FAILED", "STOP", "RECOVERY_REQUIRED");

    private final ObjectProvider<WorkflowService> workflowServiceProvider;
    private final WorkflowDefMapper defMapper;
    private final WorkflowRunMapper runMapper;

    @Override
    public String getType() {
        return NodeType.NESTED;
    }

    @Override
    public ExecResult execute(NodeContext context) {
        String workflowId = text(context.getConfig() == null ? null : context.getConfig().get(CONFIG_WORKFLOW_ID));
        if (StrUtil.isBlank(workflowId)) {
            throw new BusinessException("嵌套任务流节点未配置 " + CONFIG_WORKFLOW_ID);
        }
        if (workflowId.equals(context.getNode() == null ? null : context.getNode().getWfId())) {
            throw new BusinessException("嵌套任务流不能引用自身");
        }
        WorkflowDef child = defMapper.selectById(workflowId);
        if (child == null) {
            throw new BusinessException("子任务流不存在: " + workflowId);
        }
        if (child.getStatus() != null && child.getStatus() == 0) {
            throw new BusinessException("子任务流已禁用，无法触发: " + child.getName());
        }

        Map<String, Object> params = new HashMap<>(context.getParams() == null ? Map.of() : context.getParams());
        int depth = parseDepth(params.get(PARAM_NESTED_DEPTH)) + 1;
        if (depth > MAX_NESTED_DEPTH) {
            throw new BusinessException("嵌套任务流层数超过上限 " + MAX_NESTED_DEPTH + "，请检查是否形成递归");
        }
        params.put(PARAM_NESTED_DEPTH, depth);

        String requestId = nestedRequestId(context);
        RunAccept accept;
        try {
            accept = workflowServiceProvider.getObject().triggerWithRequest(child.getId(), params, "NESTED",
                    null, null, requestId, context.getBizDate(), null);
        } catch (BusinessException e) {
            throw new IllegalStateException("子任务流无法触发: " + e.getMessage(), e);
        }
        if (RunAccept.STATE_REJECTED.equals(accept.getState())) {
            throw new IllegalStateException("子任务流未受理: "
                    + (accept.getRejectMessage() == null ? accept.getRejectCode() : accept.getRejectMessage()));
        }
        String childRunId = accept.getRunId();
        if (StrUtil.isBlank(childRunId)) {
            throw new IllegalStateException("子任务流受理结果缺少运行ID");
        }
        boolean reused = RunAccept.STATE_DUPLICATE.equals(accept.getState());
        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds(context) * 1000L;

        while (true) {
            WorkflowRun run = runMapper.selectById(childRunId);
            String status = run == null ? null : run.getRunStatus();
            if (status != null && TERMINAL.contains(status)) {
                long cost = System.currentTimeMillis() - start;
                if ("SUCCESS".equals(status)) {
                    return ExecResult.builder()
                            .success(true)
                            .log("子任务流「" + child.getName() + "」执行成功，runId=" + childRunId
                                    + (reused ? "（重试复用既有子运行）" : "") + "，耗时 " + cost + "ms")
                            .build();
                }
                String reason = run.getErrorMessage() == null ? status : run.getErrorMessage();
                throw new IllegalStateException("子任务流「" + child.getName() + "」未成功: " + reason
                        + "，runId=" + childRunId);
            }
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw new IllegalStateException("等待子任务流超时（" + (timeoutMs / 1000)
                        + "s），子运行仍在继续: runId=" + childRunId);
            }
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待子任务流被中断: runId=" + childRunId);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待子任务流被中断: runId=" + childRunId);
            }
        }
    }

    /** 父运行 + 节点运行构成稳定幂等键；调试场景没有运行ID，用时间戳保证可执行。 */
    private String nestedRequestId(NodeContext context) {
        if (StrUtil.isBlank(context.getRunId()) || StrUtil.isBlank(context.getNodeRunId())) {
            return "nested-debug-" + System.nanoTime();
        }
        return "nested:" + context.getRunId() + ":" + context.getNodeRunId();
    }

    private long timeoutSeconds(NodeContext context) {
        Long configured = context.getTimeoutSeconds();
        return configured == null || configured <= 0 ? DEFAULT_TIMEOUT_SECONDS : configured;
    }

    private int parseDepth(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
