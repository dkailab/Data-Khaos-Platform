package com.datakhaos.schedule.handler;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.pipeline.api.model.PipelineInstanceDto;
import com.datakhaos.pipeline.api.service.PipelineApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 数据同步任务：触发管道实例并等待至终态。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncJobHandler implements JobHandler {

    private final PipelineApiClient pipelineApiClient;

    @Override
    public String type() {
        return "SYNC";
    }

    @Override
    public int execute(JobContext context) {
        var job = context.job();
        String taskId = context.refOrParam("pipelineTaskId");
        if (StrUtil.isBlank(taskId)) {
            throw new BusinessException("SYNC 任务未配置关联管道任务（refId 或 params.pipelineTaskId）");
        }
        String bizDate = context.bizDate();
        int timeout = context.timeoutSeconds(PipelineApiClient.DEFAULT_TIMEOUT_SECONDS);
        PipelineApiClient.TriggerResult triggerResult =
                pipelineApiClient.triggerSafely(taskId, "SCHEDULE", null, null, bizDate);
        PipelineInstanceDto instance = triggerResult.instance();
        if (instance == null || StrUtil.isBlank(instance.getId())) {
            throw new NoRetryException("SYNC 任务触发返回空实例，无法确认是否已受理");
        }
        log.info("SYNC 任务 {} 已触发管道任务 {}，instanceId={}{}", job.getJobName(), taskId, instance.getId(),
                triggerResult.alreadySubmitted() ? "（超时后确认已提交）" : "");
        PipelineInstanceDto finished;
        try {
            finished = pipelineApiClient.waitForInstance(instance.getId(), timeout);
        } catch (PipelineApiClient.InstanceTimeoutException e) {
            throw new NoRetryException("管道已受理但等待超时: instanceId=" + instance.getId() + "，" + e.getMessage());
        }
        if (finished.getStatus() == null || finished.getStatus() == 0) {
            throw new NoRetryException("管道实例仍未完成: instanceId=" + finished.getId());
        }
        if (Integer.valueOf(PipelineInstanceDto.COMMIT_UNCONFIRMED).equals(finished.getStatus())) {
            throw new NoRetryException("管道提交结果待核查: instanceId=" + finished.getId());
        }
        if (finished.getStatus() != 1) {
            throw new BusinessException("管道执行失败: instanceId=" + finished.getId()
                    + "，error=" + (finished.getErrorMessage() == null ? "-" : finished.getErrorMessage()));
        }
        long rows = finished.getRows() == null ? 0 : finished.getRows();
        log.info("SYNC 任务 {} 完成，instanceId={}，影响行数 {}", job.getJobName(), finished.getId(), rows);
        return (int) Math.min(rows, Integer.MAX_VALUE);
    }
}
