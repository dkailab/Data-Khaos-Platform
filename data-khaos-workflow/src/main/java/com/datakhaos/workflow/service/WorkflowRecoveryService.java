package com.datakhaos.workflow.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.workflow.dto.WorkflowRecoveryView;
import com.datakhaos.workflow.entity.WorkflowDef;
import com.datakhaos.workflow.entity.WorkflowEdge;
import com.datakhaos.workflow.entity.WorkflowExternalRecovery;
import com.datakhaos.workflow.entity.WorkflowNode;
import com.datakhaos.workflow.entity.WorkflowNodeRun;
import com.datakhaos.workflow.entity.WorkflowRun;
import com.datakhaos.workflow.executor.DolphinSchedulerBinding;
import com.datakhaos.workflow.executor.DolphinSchedulerClient;
import com.datakhaos.workflow.executor.DolphinSchedulerNodeExecutor;
import com.datakhaos.workflow.executor.NodeType;
import com.datakhaos.workflow.mapper.WorkflowDefMapper;
import com.datakhaos.workflow.mapper.WorkflowEdgeMapper;
import com.datakhaos.workflow.mapper.WorkflowExternalRecoveryMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeMapper;
import com.datakhaos.workflow.mapper.WorkflowNodeRunMapper;
import com.datakhaos.workflow.mapper.WorkflowRunMapper;
import com.datakhaos.workflow.runner.WorkflowRunLifecycle;
import com.datakhaos.workflow.runner.WorkflowRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * DolphinScheduler 失败节点恢复。
 *
 * <p>恢复动作只对已关联远端实例的失败节点开放。远端使用
 * {@code START_FAILURE_TASK_PROCESS}，实例编号保持不变；本地在远端到达 SUCCESS 后
 * 才回写节点成功并续跑下游，避免把远端成功误报成本地运行成功。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowRecoveryService {

    public static final String ACTION = "START_FAILURE_TASK_PROCESS";
    private static final String DEVELOP = "task:develop";
    private static final String SUBMITTING = "SUBMITTING";
    private static final String ACCEPTED = "ACCEPTED";
    private static final String POLLING = "POLLING";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String UNKNOWN = "UNKNOWN";
    private static final Set<String> ACTIVE_STATES = Set.of(
            "SUBMITTED_SUCCESS", "RUNNING_EXECUTION", "READY_PAUSE", "READY_STOP",
            "SERIAL_WAIT", "FAILOVER", "WAITING_THREAD", "WAITING_DEPEND",
            "DELAY_EXECUTION", "DISPATCH", "READY_BLOCK", "BLOCKED", "WAIT_TO_RUN");
    private static final Set<String> FAILURE_STATES = Set.of("FAILURE", "STOP", "KILL");

    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final WorkflowDefMapper defMapper;
    private final WorkflowProjectAccess projectAccess;
    private final WorkflowExternalRecoveryMapper recoveryMapper;
    private final WorkflowRunLifecycle lifecycle;
    private final WorkflowRunner runner;
    private final DolphinSchedulerClient client;
    private final DolphinSchedulerNodeExecutor executor;
    private final ObjectMapper json;

    @Value("${workflow.dolphinscheduler.recovery-timeout-seconds:600}")
    private long recoveryTimeoutSeconds;

    /**
     * 从失败节点恢复。提交成功后立即返回；远端终态由定时轮询推进，
     * 轮询超时保留 UNKNOWN 供人工核查，不重复提交。
     */
    public WorkflowRecoveryView recover(String runId, String nodeRunId) {
        Context context = load(runId, nodeRunId, true);
        WorkflowRun run = context.run;
        WorkflowNodeRun node = context.node;
        validateLocalState(run, node);
        DolphinSchedulerBinding binding = binding(node);
        if (!client.enabled()) {
            throw new BusinessException("DolphinScheduler 试点未配置，无法恢复");
        }
        verifyDefinition(binding);

        DolphinSchedulerClient.RemoteInstance before = query(binding, node.getRefId());
        String state = normalize(before.state());
        if ("SUCCESS".equals(state)) {
            throw new BusinessException("远端实例已成功，无需从失败节点恢复；请刷新本地运行状态");
        }
        if (ACTIVE_STATES.contains(state)) {
            throw new BusinessException("远端实例仍在执行（" + state + "），暂不能从失败节点恢复");
        }
        if (!FAILURE_STATES.contains(state)) {
            throw new BusinessException("远端实例当前状态为 " + display(state) + "，不支持从失败节点恢复");
        }

        Claim claim = claim(context, before);
        WorkflowExternalRecovery recovery = claim.recovery;
        if (!claim.submit) {
            return WorkflowRecoveryView.of(recovery);
        }

        // 并发点击可能已由另一个请求提交；POST 前再核对一次远端状态。
        DolphinSchedulerClient.RemoteInstance recheck = query(binding, node.getRefId());
        String recheckState = normalize(recheck.state());
        if ("SUCCESS".equals(recheckState)) {
            markSucceeded(recovery, recheck, "远端实例已成功，无需重复恢复");
            resumeLocal(recovery, context, "远端实例已成功");
            return latestView(recovery);
        }
        if (ACTIVE_STATES.contains(recheckState)) {
            markAccepted(recovery, recheck, "远端已接受恢复请求，实例运行中");
            return WorkflowRecoveryView.of(recovery);
        }
        if (!FAILURE_STATES.contains(recheckState)) {
            markFailed(recovery, recheck, "远端状态已变为 " + display(recheckState) + "，未提交恢复");
            throw new BusinessException("远端状态已变为 " + display(recheckState) + "，未提交恢复，请刷新后重试");
        }

        try {
            client.recoverFailure(binding, node.getRefId());
            markAccepted(recovery, recheck, "恢复请求已提交，等待远端执行");
        } catch (RuntimeException e) {
            log.warn("DolphinScheduler 恢复提交结果不确定 nodeRunId={} instance={} error={}",
                    node.getId(), node.getRefId(), e.getMessage());
            disambiguateAfterSubmit(recovery, context, binding);
        }
        return latestView(recovery);
    }

    /** 查询最近一次恢复记录；无记录返回 null。 */
    public WorkflowRecoveryView latest(String runId, String nodeRunId) {
        Context context = load(runId, nodeRunId, true);
        WorkflowExternalRecovery recovery = recoveryMapper.find(context.node.getId(), ACTION);
        return recovery == null ? null : WorkflowRecoveryView.of(recovery);
    }

    /** 定时推进已提交的恢复请求；进程重启后仍可从 ACCEPTED/POLLING 继续。 */
    @Scheduled(fixedDelayString = "${workflow.dolphinscheduler.recovery-poll-ms:15000}")
    public void pollRecoveries() {
        List<WorkflowExternalRecovery> rows = recoveryMapper.pollable(LocalDateTime.now().minusSeconds(5));
        for (WorkflowExternalRecovery row : rows) {
            try {
                pollOne(row.getId());
            } catch (RuntimeException e) {
                log.warn("轮询 DolphinScheduler 恢复状态失败 recoveryId={}", row.getId(), e);
            }
        }
    }

    /** 单个恢复请求的轮询入口，测试可直接调用。 */
    public void pollOne(String recoveryId) {
        WorkflowExternalRecovery recovery = recoveryMapper.selectById(recoveryId);
        if (recovery == null || !(ACCEPTED.equals(recovery.getStatus()) || POLLING.equals(recovery.getStatus()))) {
            return;
        }
        Context context = load(recovery.getRunId(), recovery.getNodeRunId(), false);
        DolphinSchedulerBinding binding;
        try {
            binding = binding(context.node);
        } catch (RuntimeException e) {
            markFailed(recovery, null, "远端映射缺失，无法继续恢复: " + e.getMessage());
            return;
        }
        DolphinSchedulerClient.RemoteInstance remote;
        try {
            remote = client.queryInstance(binding, context.node.getRefId());
        } catch (RuntimeException e) {
            timeoutOrKeepPolling(recovery, null, "暂时无法读取远端实例状态");
            return;
        }
        String state = normalize(remote.state());
        touch(recovery, remote, state);
        if ("SUCCESS".equals(state)) {
            markSucceeded(recovery, remote, "远端实例恢复成功");
            resumeLocal(recovery, context, "远端实例恢复成功");
            return;
        }
        if (FAILURE_STATES.contains(state)) {
            markFailed(recovery, remote, "远端恢复后仍失败: " + state);
            return;
        }
        if (ACTIVE_STATES.contains(state)) {
            markPolling(recovery, remote, "远端实例执行中: " + state);
            return;
        }
        timeoutOrKeepPolling(recovery, remote, "远端实例状态 " + display(state) + " 暂不可识别");
    }

    private void disambiguateAfterSubmit(WorkflowExternalRecovery recovery, Context context,
                                         DolphinSchedulerBinding binding) {
        try {
            DolphinSchedulerClient.RemoteInstance after = client.queryInstance(binding, context.node.getRefId());
            String state = normalize(after.state());
            if ("SUCCESS".equals(state)) {
                markSucceeded(recovery, after, "恢复请求结果不确定，远端实例已成功");
                resumeLocal(recovery, context, "远端实例恢复成功");
            } else if (ACTIVE_STATES.contains(state)) {
                markAccepted(recovery, after, "恢复请求结果不确定，远端实例已开始执行");
            } else {
                markFailed(recovery, after, "恢复请求结果不确定，远端状态: " + display(state));
            }
        } catch (RuntimeException e) {
            markUnknown(recovery, "恢复请求结果不确定，请稍后核对远端实例状态");
        }
    }

    private void resumeLocal(WorkflowExternalRecovery recovery, Context context, String remoteMessage) {
        if ("RESUMING".equals(recovery.getLocalResumeStatus())
                || "COMPLETED".equals(recovery.getLocalResumeStatus())) {
            return;
        }
        WorkflowRun run = context.run;
        WorkflowNodeRun node = context.node;
        Set<String> scope = descendants(node.getNodeCode(), context.def.getId());
        scope.add(node.getNodeCode());
        LocalDateTime now = LocalDateTime.now();
        try {
            if (!lifecycle.prepareResume(run.getId(), now)) {
                WorkflowRun current = runMapper.selectById(run.getId());
                if (current != null && "SUCCESS".equals(current.getRunStatus())) {
                    updateLocalResume(recovery, "COMPLETED", remoteMessage + "；本地运行已完成");
                } else {
                    updateLocalResume(recovery, "RESUMING", remoteMessage + "；本地运行正在续跑");
                }
                return;
            }
            long duration = node.getStartTime() == null ? 0
                    : Math.max(Duration.between(node.getStartTime(), now).toMillis(), 0);
            if (lifecycle.markNodeRecovered(node.getId(),
                    "DolphinScheduler 实例 #" + node.getRefId() + " 恢复成功（" + remoteMessage + "）",
                    duration, now) != 1) {
                lifecycle.failResume(run.getId(), "远端已成功，但本地节点状态已变化，请核查", now);
                updateLocalResume(recovery, "RESUME_FAILED", remoteMessage + "；本地节点状态已变化");
                return;
            }
            lifecycle.resetSkippedNodes(run.getId(),
                    nodeRunMapper.selectList(Wrappers.<WorkflowNodeRun>lambdaQuery()
                                    .eq(WorkflowNodeRun::getRunId, run.getId())
                                    .eq(WorkflowNodeRun::getStatus, "SKIPPED"))
                            .stream().map(WorkflowNodeRun::getNodeCode).filter(scope::contains).toList(),
                    now);
            updateLocalResume(recovery, "RESUMING", remoteMessage + "；已开始续跑本地下游");
            runner.resumeAsync(run.getId(), run.getWfId(), runtimeParams(run), run.getBizDate(), scope);
        } catch (RuntimeException e) {
            log.warn("本地恢复续跑提交失败 runId={} nodeRunId={}", run.getId(), node.getId(), e);
            lifecycle.failResume(run.getId(), "远端已成功，但本地续跑提交失败，请核查", now);
            updateLocalResume(recovery, "RESUME_FAILED", remoteMessage + "；本地续跑提交失败");
        }
    }

    private Set<String> descendants(String nodeCode, String wfId) {
        List<WorkflowEdge> edges = edgeMapper.selectList(Wrappers.<WorkflowEdge>lambdaQuery()
                .eq(WorkflowEdge::getWfId, wfId));
        Map<String, List<String>> adjacency = new HashMap<>();
        for (WorkflowEdge edge : edges) {
            adjacency.computeIfAbsent(edge.getFromCode(), key -> new java.util.ArrayList<>())
                    .add(edge.getToCode());
        }
        Set<String> result = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(nodeCode);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (result.add(next)) {
                    queue.add(next);
                }
            }
        }
        return result;
    }

    private Map<String, Object> runtimeParams(WorkflowRun run) {
        if (StrUtil.isBlank(run.getTriggerParams())) {
            return Map.of();
        }
        try {
            return JSONUtil.parseObj(run.getTriggerParams());
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private Context load(String runId, String nodeRunId, boolean requireAccess) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "运行实例不存在或不可访问");
        }
        WorkflowDef def = defMapper.selectById(run.getWfId());
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "运行实例不存在或不可访问");
        }
        if (requireAccess) {
            projectAccess.requireAction(def.getProjectId(), DEVELOP);
            if (StrUtil.isNotBlank(run.getProjectGroupId())
                    && !Objects.equals(def.getProjectId(), run.getProjectGroupId())) {
                projectAccess.requireAction(run.getProjectGroupId(), DEVELOP);
            }
        }
        WorkflowNodeRun node = nodeRunMapper.selectById(nodeRunId);
        if (node == null || !runId.equals(node.getRunId()) || !run.getWfId().equals(node.getWfId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "节点运行不存在或不可访问");
        }
        return new Context(run, def, node);
    }

    private void validateLocalState(WorkflowRun run, WorkflowNodeRun node) {
        if (!NodeType.DOLPHINSCHEDULER.equals(node.getNodeType())
                || !NodeType.DOLPHINSCHEDULER.equals(node.getRefType())) {
            throw new BusinessException("该节点不是已关联实例的 DolphinScheduler 节点，无法恢复");
        }
        if (node.getRefId() == null || !node.getRefId().matches("[1-9][0-9]*")) {
            throw new BusinessException("该节点尚未关联可恢复的 DolphinScheduler 实例");
        }
        if (!"FAILED".equals(run.getRunStatus()) && !"RECOVERY_REQUIRED".equals(run.getRunStatus())) {
            throw new BusinessException("当前运行状态为 " + display(run.getRunStatus())
                    + "，只有失败或待恢复的运行可以执行恢复");
        }
        if (!"FAILED".equals(node.getStatus())) {
            throw new BusinessException("当前节点状态为 " + display(node.getStatus())
                    + "，只有失败节点可以执行恢复");
        }
    }

    private DolphinSchedulerBinding binding(WorkflowNodeRun node) {
        DolphinSchedulerBinding binding;
        try {
            binding = executor.bindingFor(node, null);
        } catch (RuntimeException e) {
            throw new BusinessException("该节点缺少 DolphinScheduler 远端映射，无法恢复");
        }
        if (binding == null || !binding.valid()
                || !node.getWfId().equals(binding.workflowId())
                || !node.getNodeCode().equals(binding.nodeCode())) {
            throw new BusinessException("该节点的 DolphinScheduler 远端映射与工作流节点不一致，已拒绝恢复");
        }
        return binding;
    }

    private void verifyDefinition(DolphinSchedulerBinding binding) {
        DolphinSchedulerClient.RemoteDefinition remote;
        try {
            remote = client.queryDefinition(binding);
        } catch (RuntimeException e) {
            throw new BusinessException("暂时无法校验 DolphinScheduler 工作流定义，请稍后重试");
        }
        if (remote.projectCode() > 0 && remote.projectCode() != binding.projectCode()) {
            throw new BusinessException("DolphinScheduler 工作流定义不属于配置的项目，已拒绝恢复");
        }
        if (!remote.online()) {
            throw new BusinessException("DolphinScheduler 工作流定义未上线，已拒绝恢复");
        }
        if (remote.version() <= 0) {
            throw new BusinessException("无法确认 DolphinScheduler 工作流定义版本，已拒绝恢复");
        }
        if (binding.definitionVersion() != null && binding.definitionVersion() != remote.version()) {
            throw new BusinessException("DolphinScheduler 工作流定义版本不一致（期望 V"
                    + binding.definitionVersion() + "，远端 V" + remote.version() + "），已拒绝恢复");
        }
    }

    private DolphinSchedulerClient.RemoteInstance query(DolphinSchedulerBinding binding, String instanceId) {
        try {
            return client.queryInstance(binding, instanceId);
        } catch (RuntimeException e) {
            throw new BusinessException("暂时无法读取 DolphinScheduler 实例状态，请稍后重试");
        }
    }

    private Claim claim(Context context, DolphinSchedulerClient.RemoteInstance before) {
        WorkflowExternalRecovery existing = recoveryMapper.find(context.node.getId(), ACTION);
        if (existing != null) {
            String status = existing.getStatus();
            if (ACCEPTED.equals(status) || POLLING.equals(status) || SUCCEEDED.equals(status)) {
                return new Claim(existing, false);
            }
            if (SUBMITTING.equals(status) && existing.getUpdateTime() != null
                    && existing.getUpdateTime().isAfter(LocalDateTime.now().minusMinutes(2))) {
                return new Claim(existing, false);
            }
            boolean claimed = recoveryMapper.update(null, Wrappers.<WorkflowExternalRecovery>lambdaUpdate()
                    .eq(WorkflowExternalRecovery::getId, existing.getId())
                    .eq(WorkflowExternalRecovery::getStatus, status)
                    .set(WorkflowExternalRecovery::getStatus, SUBMITTING)
                    .set(WorkflowExternalRecovery::getOperatorId, operator())
                    .set(WorkflowExternalRecovery::getRemoteStateBefore, before.state())
                    .set(WorkflowExternalRecovery::getRemoteRunTimes, before.runTimes())
                    .set(WorkflowExternalRecovery::getMessage, "正在提交恢复请求")
                    .set(WorkflowExternalRecovery::getUpdateTime, LocalDateTime.now())) == 1;
            WorkflowExternalRecovery current = claimed ? recoveryMapper.selectById(existing.getId())
                    : recoveryMapper.find(context.node.getId(), ACTION);
            return new Claim(current, claimed);
        }

        WorkflowExternalRecovery recovery = new WorkflowExternalRecovery();
        recovery.setRunId(context.run.getId());
        recovery.setNodeRunId(context.node.getId());
        recovery.setWfId(context.run.getWfId());
        recovery.setProjectId(context.def.getProjectId());
        recovery.setRuntime(DolphinSchedulerBinding.RUNTIME);
        recovery.setRemoteProjectCode(binding(context.node).projectCode());
        recovery.setRemoteInstanceId(Long.valueOf(context.node.getRefId()));
        recovery.setAction(ACTION);
        recovery.setStatus(SUBMITTING);
        recovery.setOperatorId(operator());
        recovery.setRemoteStateBefore(before == null ? null : before.state());
        recovery.setRemoteRunTimes(before == null ? null : before.runTimes());
        recovery.setMessage("正在提交恢复请求");
        recovery.setPollCount(0);
        recovery.setCreateTime(LocalDateTime.now());
        recovery.setUpdateTime(LocalDateTime.now());
        try {
            recoveryMapper.insert(recovery);
            return new Claim(recovery, true);
        } catch (DuplicateKeyException e) {
            WorkflowExternalRecovery concurrent = recoveryMapper.find(context.node.getId(), ACTION);
            if (concurrent == null) {
                throw new BusinessException("恢复请求已被其他操作领取，请稍后刷新");
            }
            return new Claim(concurrent, false);
        }
    }

    private void touch(WorkflowExternalRecovery recovery, DolphinSchedulerClient.RemoteInstance remote, String state) {
        recoveryMapper.update(null, Wrappers.<WorkflowExternalRecovery>lambdaUpdate()
                .eq(WorkflowExternalRecovery::getId, recovery.getId())
                .set(WorkflowExternalRecovery::getPollCount,
                        recovery.getPollCount() == null ? 1 : recovery.getPollCount() + 1)
                .set(WorkflowExternalRecovery::getLastCheckedAt, LocalDateTime.now())
                .set(WorkflowExternalRecovery::getRemoteStateAfter, state)
                .set(WorkflowExternalRecovery::getRemoteRunTimes, remote.runTimes())
                .set(WorkflowExternalRecovery::getUpdateTime, LocalDateTime.now()));
    }

    private void markAccepted(WorkflowExternalRecovery recovery, DolphinSchedulerClient.RemoteInstance remote,
                              String message) {
        update(recovery, ACCEPTED, remote, message, null);
    }

    private void markPolling(WorkflowExternalRecovery recovery, DolphinSchedulerClient.RemoteInstance remote,
                             String message) {
        update(recovery, POLLING, remote, message, null);
    }

    private void markSucceeded(WorkflowExternalRecovery recovery, DolphinSchedulerClient.RemoteInstance remote,
                               String message) {
        update(recovery, SUCCEEDED, remote, message, null);
    }

    private void markFailed(WorkflowExternalRecovery recovery, DolphinSchedulerClient.RemoteInstance remote,
                            String message) {
        update(recovery, FAILED, remote, message, null);
    }

    private void markUnknown(WorkflowExternalRecovery recovery, String message) {
        update(recovery, UNKNOWN, null, message, null);
    }

    private void timeoutOrKeepPolling(WorkflowExternalRecovery recovery,
                                      DolphinSchedulerClient.RemoteInstance remote, String message) {
        LocalDateTime created = recovery.getCreateTime();
        if (created != null && created.plusSeconds(recoveryTimeoutSeconds).isBefore(LocalDateTime.now())) {
            update(recovery, UNKNOWN, remote, message + "，等待超时，请核对远端实例", null);
        } else {
            update(recovery, POLLING, remote, message, null);
        }
    }

    private void update(WorkflowExternalRecovery recovery, String status,
                        DolphinSchedulerClient.RemoteInstance remote, String message, String localResumeStatus) {
        var update = Wrappers.<WorkflowExternalRecovery>lambdaUpdate()
                .eq(WorkflowExternalRecovery::getId, recovery.getId())
                .set(WorkflowExternalRecovery::getStatus, status)
                .set(WorkflowExternalRecovery::getMessage, truncate(message, 1000))
                .set(WorkflowExternalRecovery::getUpdateTime, LocalDateTime.now());
        if (remote != null) {
            if (remote.state() != null) {
                update.set(WorkflowExternalRecovery::getRemoteStateAfter, remote.state());
            }
            if (remote.runTimes() != null) {
                update.set(WorkflowExternalRecovery::getRemoteRunTimes, remote.runTimes());
            }
            update.set(WorkflowExternalRecovery::getLastCheckedAt, LocalDateTime.now());
        }
        if (localResumeStatus != null) {
            update.set(WorkflowExternalRecovery::getLocalResumeStatus, localResumeStatus);
        }
        recoveryMapper.update(null, update);
        recovery.setStatus(status);
        recovery.setMessage(message);
        recovery.setUpdateTime(LocalDateTime.now());
        if (localResumeStatus != null) {
            recovery.setLocalResumeStatus(localResumeStatus);
        }
    }

    private void updateLocalResume(WorkflowExternalRecovery recovery, String status, String message) {
        recoveryMapper.update(null, Wrappers.<WorkflowExternalRecovery>lambdaUpdate()
                .eq(WorkflowExternalRecovery::getId, recovery.getId())
                .set(WorkflowExternalRecovery::getLocalResumeStatus, status)
                .set(WorkflowExternalRecovery::getMessage, truncate(message, 1000))
                .set(WorkflowExternalRecovery::getUpdateTime, LocalDateTime.now()));
        recovery.setLocalResumeStatus(status);
        recovery.setMessage(message);
    }

    private WorkflowRecoveryView latestView(WorkflowExternalRecovery recovery) {
        WorkflowExternalRecovery current = recoveryMapper.selectById(recovery.getId());
        return WorkflowRecoveryView.of(current == null ? recovery : current);
    }

    private String operator() {
        return StrUtil.blankToDefault(MetadataHolder.getUserId(), "system");
    }

    private String normalize(String state) {
        return state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
    }

    private String display(String state) {
        return StrUtil.isBlank(state) ? "未知" : state;
    }

    private String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    private record Context(WorkflowRun run, WorkflowDef def, WorkflowNodeRun node) {
    }

    private record Claim(WorkflowExternalRecovery recovery, boolean submit) {
    }
}
