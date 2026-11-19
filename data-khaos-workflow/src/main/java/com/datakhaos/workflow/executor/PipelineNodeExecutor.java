package com.datakhaos.workflow.executor;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.pipeline.api.model.PipelineInstanceDto;
import com.datakhaos.pipeline.api.service.PipelineApiClient;
import com.datakhaos.workflow.entity.WorkflowNodeRun;
import com.datakhaos.workflow.mapper.WorkflowNodeRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 管道任务节点执行器：触发指定 pipeline 任务并等待其实例到达终态。
 *
 * <p>节点配置（configJson）：{@code {"pipelineTaskId": "..."}}。
 * 触发采用「超时后反查实例」的幂等保护，避免远程超时导致重复提交。
 * 成功后把 pipeline 实例ID 回写到节点运行记录的 refType/refId，形成可追溯关联。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PipelineNodeExecutor implements NodeExecutor {

    /** 节点配置键：管道任务ID */
    public static final String CONFIG_PIPELINE_TASK_ID = "pipelineTaskId";

    /** 默认等待超时（秒），节点未配置 timeout 时使用 */
    private static final int DEFAULT_TIMEOUT_SECONDS = PipelineApiClient.DEFAULT_TIMEOUT_SECONDS;

    private final PipelineApiClient pipelineApiClient;
    private final WorkflowNodeRunMapper nodeRunMapper;

    @Override
    public String getType() {
        return NodeType.PIPELINE;
    }

    @Override
    public ExecResult execute(NodeContext context) throws Exception {
        Object taskIdObj = context.getConfig() == null ? null : context.getConfig().get(CONFIG_PIPELINE_TASK_ID);
        String taskId = taskIdObj == null ? null : String.valueOf(taskIdObj).trim();
        if (StrUtil.isBlank(taskId)) {
            throw new IllegalArgumentException("PIPELINE 节点未配置 " + CONFIG_PIPELINE_TASK_ID);
        }

        int timeoutSeconds = context.getTimeoutSeconds() == null || context.getTimeoutSeconds() <= 0
                ? DEFAULT_TIMEOUT_SECONDS : context.getTimeoutSeconds().intValue();

        long start = System.currentTimeMillis();
        // 触发（带幂等保护）
        PipelineApiClient.TriggerResult triggerResult = pipelineApiClient.triggerSafely(
                taskId, "WORKFLOW", context.getRunId(), context.getNodeRunId());
        PipelineInstanceDto instance = triggerResult.instance();
        if (instance == null || StrUtil.isBlank(instance.getId())) {
            throw new PipelineApiClient.SubmissionUnconfirmedException("管道触发返回空实例", null);
        }

        // 回写关联 ID
        writeRef(context.getNodeRunId(), instance.getId());

        String triggerLog = triggerResult.alreadySubmitted()
                ? "管道实例已存在（触发调用超时后确认提交），instanceId=" + instance.getId()
                : "管道已受理，instanceId=" + instance.getId();

        // 等待终态
        PipelineInstanceDto finished;
        try {
            finished = pipelineApiClient.waitForInstance(instance.getId(), timeoutSeconds);
        } catch (PipelineApiClient.InstanceTimeoutException e) {
            throw new PipelineApiClient.SubmissionUnconfirmedException(
                    "节点等待管道完成超时: instanceId=" + instance.getId() + "，" + e.getMessage(), e);
        }

        long cost = System.currentTimeMillis() - start;
        if (finished.getStatus() == null || finished.getStatus() == 0) {
            throw new IllegalStateException("管道实例仍未完成（status=0）");
        }
        if (finished.getStatus() != 1) {
            String msg = StrUtil.isBlank(finished.getErrorMessage()) ? "无错误详情" : finished.getErrorMessage();
            throw new IllegalStateException("管道执行失败: " + msg);
        }
        long rows = finished.getRows() == null ? 0 : finished.getRows();
        return ExecResult.builder()
                .success(true)
                .rows((int) Math.min(rows, Integer.MAX_VALUE))
                .log(triggerLog + "；执行成功，耗时 " + cost + "ms，影响行数 " + rows)
                .build();
    }

    /** 回写节点运行记录与管道实例的关联 */
    private void writeRef(String nodeRunId, String instanceId) {
        if (StrUtil.isBlank(nodeRunId)) {
            return;
        }
        try {
            WorkflowNodeRun patch = new WorkflowNodeRun();
            patch.setId(nodeRunId);
            patch.setRefType(NodeType.PIPELINE);
            patch.setRefId(instanceId);
            nodeRunMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("回写节点关联ID失败: nodeRunId={}, instanceId={}, error={}", nodeRunId, instanceId, e.getMessage());
        }
    }
}
