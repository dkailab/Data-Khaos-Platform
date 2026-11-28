package com.datakhaos.workflow.executor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.entity.WorkflowNodeRun;
import com.datakhaos.workflow.mapper.WorkflowNodeRunMapper;
import com.datakhaos.workflow.service.DolphinSchedulerBindingResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * DolphinScheduler 离线 DAG 节点执行器。
 *
 * <p>映射优先来自节点运行记录中的 {@code refContext}（受理时从发布快照解析），
 * 因此发布后编辑草稿不会改变已受理运行使用的远端定义。旧版单节点试点可用
 * 环境变量回退。提交前校验远端定义仍在线且版本一致，避免启动接口解析到
 * 另一个已上线版本。</p>
 */
@Component
@RequiredArgsConstructor
public class DolphinSchedulerNodeExecutor implements NodeExecutor {
    public static final String SUBMITTING = "SUBMITTING";
    private static final Set<String> ACTIVE_STATES = Set.of(
            "SUBMITTED_SUCCESS", "RUNNING_EXECUTION", "READY_PAUSE", "READY_STOP",
            "SERIAL_WAIT", "FAILOVER", "WAITING_THREAD", "WAITING_DEPEND",
            "DELAY_EXECUTION", "DISPATCH", "READY_BLOCK", "BLOCKED", "WAIT_TO_RUN");
    private static final Set<String> UNSUCCESSFUL_STATES = Set.of("FAILURE", "STOP", "KILL");

    private final WorkflowNodeRunMapper runs;
    private final DolphinSchedulerClient client;
    private final DolphinSchedulerBindingResolver bindings;
    private final ObjectMapper json;

    @Override
    public String getType() {
        return NodeType.DOLPHINSCHEDULER;
    }

    @Override
    public ExecResult execute(NodeContext context) {
        if (!client.enabled() || context.getNodeRunId() == null) {
            throw new BusinessException("DolphinScheduler 试点未配置或当前请求不是工作流运行");
        }
        WorkflowNodeRun run = runs.selectById(context.getNodeRunId());
        if (run == null || !run.getWfId().equals(context.getNode().getWfId())
                || !run.getNodeCode().equals(context.getNode().getNodeCode())
                || !getType().equals(run.getNodeType()) || !run.getRunId().equals(context.getRunId())) {
            throw new BusinessException("当前工作流节点未绑定 DolphinScheduler 试点");
        }
        DolphinSchedulerBinding binding = bindingFor(run, context);
        String externalId = run.getRefId();
        if (externalId == null) {
            binding = verifyDefinition(binding);
            externalId = submit(run, binding, context.getBizDate());
        }
        if (SUBMITTING.equals(externalId)
                || !externalId.matches("[1-9][0-9]*")
                || (run.getRefType() != null && !getType().equals(run.getRefType()))) {
            throw new UnconfirmedExecutionException("DolphinScheduler 提交尚未确认，请按节点运行 ID 核对远端实例");
        }
        return awaitResult(binding, externalId, context.getTimeoutSeconds());
    }

    /** 读取节点已关联的远端实例状态，不提交、不改变本地运行。 */
    public String queryState(WorkflowNodeRun run) {
        DolphinSchedulerBinding binding = bindingFor(run, null);
        if (binding == null || !getType().equals(run.getRefType())
                || run.getRefId() == null || !run.getRefId().matches("[1-9][0-9]*")) {
            throw new BusinessException("该节点暂无可查询的 DolphinScheduler 实例");
        }
        try {
            String state = client.queryInstance(binding, run.getRefId()).state();
            if (state != null && !state.isBlank()) {
                return state;
            }
        } catch (RuntimeException ignored) {
            // 连接细节和远端响应体不进入用户可见错误
        }
        throw new BusinessException("暂时无法读取 DolphinScheduler 实例状态，请重试");
    }

    /** 节点运行记录中的远端映射；无 refContext 时按节点配置或旧版环境变量回退。 */
    public DolphinSchedulerBinding bindingFor(WorkflowNodeRun run, NodeContext context) {
        DolphinSchedulerBinding stored = DolphinSchedulerBinding.fromJson(run.getRefContext(), json);
        if (stored != null && stored.valid()
                && stored.workflowId().equals(run.getWfId())
                && stored.nodeCode().equals(run.getNodeCode())) {
            return stored;
        }
        if (context != null) {
            DolphinSchedulerBinding configured = DolphinSchedulerBinding.fromConfig(
                    context.getConfig(), run.getWfId(), run.getNodeCode());
            if (configured != null && configured.valid()) {
                return configured;
            }
        }
        DolphinSchedulerBinding legacy = bindings.legacyBinding(run.getWfId(), run.getNodeCode());
        if (legacy != null && legacy.valid()) {
            return legacy;
        }
        throw new BusinessException("当前 DolphinScheduler 节点缺少远端项目/定义映射，请重新配置并发布");
    }

    private DolphinSchedulerBinding verifyDefinition(DolphinSchedulerBinding binding) {
        DolphinSchedulerClient.RemoteDefinition remote;
        try {
            remote = client.queryDefinition(binding);
        } catch (RuntimeException e) {
            throw new BusinessException("暂时无法校验 DolphinScheduler 工作流定义，请稍后重试");
        }
        if (remote.projectCode() > 0 && remote.projectCode() != binding.projectCode()) {
            throw new BusinessException("DolphinScheduler 工作流定义不属于配置的项目，已拒绝提交");
        }
        if (!remote.online()) {
            throw new BusinessException("DolphinScheduler 工作流定义未上线，已拒绝提交");
        }
        if (remote.version() <= 0) {
            throw new BusinessException("无法确认 DolphinScheduler 工作流定义版本，已拒绝提交");
        }
        if (binding.definitionVersion() != null && binding.definitionVersion() != remote.version()) {
            throw new BusinessException("DolphinScheduler 工作流定义版本不一致（期望 V"
                    + binding.definitionVersion() + "，远端 V" + remote.version() + "），已拒绝提交");
        }
        return binding.withDefinitionVersion(remote.version());
    }

    private String submit(WorkflowNodeRun run, DolphinSchedulerBinding binding, String bizDate) {
        if (bizDate == null || !bizDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new BusinessException("DolphinScheduler 试点需要业务日期");
        }
        if (runs.update(null, Wrappers.<WorkflowNodeRun>lambdaUpdate()
                .eq(WorkflowNodeRun::getId, run.getId())
                .isNull(WorkflowNodeRun::getRefId)
                .set(WorkflowNodeRun::getRefType, getType())
                .set(WorkflowNodeRun::getRefId, SUBMITTING)
                .set(WorkflowNodeRun::getRefContext, binding.toJson(json))) != 1) {
            throw new UnconfirmedExecutionException("DolphinScheduler 提交已被其他执行器领取");
        }

        try {
            long instanceId = client.start(binding, bizDate, run.getId());
            String externalId = Long.toString(instanceId);
            if (runs.update(null, Wrappers.<WorkflowNodeRun>lambdaUpdate()
                    .eq(WorkflowNodeRun::getId, run.getId())
                    .eq(WorkflowNodeRun::getRefId, SUBMITTING)
                    .set(WorkflowNodeRun::getRefId, externalId)) != 1) {
                throw new IllegalStateException("DolphinScheduler 实例关联写入失败");
            }
            return externalId;
        } catch (Exception e) {
            throw new UnconfirmedExecutionException("DolphinScheduler 提交未确认，节点运行 ID " + run.getId());
        }
    }

    private ExecResult awaitResult(DolphinSchedulerBinding binding, String externalId, Long timeoutSeconds) {
        long timeout = TimeUnit.SECONDS.toNanos(timeoutSeconds == null || timeoutSeconds <= 0 ? 300 : timeoutSeconds);
        long started = System.nanoTime();
        do {
            String state;
            try {
                state = client.queryInstance(binding, externalId).state();
            } catch (RuntimeException e) {
                state = "";
            }
            if ("SUCCESS".equals(state) || UNSUCCESSFUL_STATES.contains(state)) {
                return ExecResult.builder().success("SUCCESS".equals(state))
                        .log("DolphinScheduler 实例 " + externalId + " 状态 " + state).build();
            }
            if ("PAUSE".equals(state)) {
                throw new UnconfirmedExecutionException("DolphinScheduler 实例 " + externalId + " 已暂停，等待恢复");
            }
            if (!state.isEmpty() && !ACTIVE_STATES.contains(state)) {
                throw new UnconfirmedExecutionException("DolphinScheduler 实例 " + externalId + " 返回未知状态 " + state);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new UnconfirmedExecutionException("DolphinScheduler 实例 " + externalId + " 状态确认被中断");
            }
            long remaining = timeout - (System.nanoTime() - started);
            if (remaining <= 0) {
                break;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.SECONDS.toNanos(2)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UnconfirmedExecutionException("DolphinScheduler 实例 " + externalId + " 状态确认被中断");
            }
        } while (System.nanoTime() - started < timeout);
        throw new UnconfirmedExecutionException("DolphinScheduler 实例 " + externalId + " 尚未确认终态");
    }

    public static class UnconfirmedExecutionException extends RuntimeException {
        public UnconfirmedExecutionException(String message) {
            super(message);
        }
    }
}
