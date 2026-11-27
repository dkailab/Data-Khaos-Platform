package com.datakhaos.schedule.handler;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.api.model.RunAcceptDto;
import com.datakhaos.workflow.api.model.WorkflowRunDto;
import com.datakhaos.workflow.api.service.WorkflowApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/** 工作流任务：触发后等待运行至终态，超时视为「已受理未完成」不再重试。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowJobHandler implements JobHandler {

    private final WorkflowApiClient workflowApiClient;

    @Override
    public String type() {
        return "WORKFLOW";
    }

    @Override
    public int execute(JobContext context) {
        var job = context.job();
        var jobLog = context.log();
        String wfId = context.refOrParam("workflowId");
        if (StrUtil.isBlank(wfId)) {
            throw new BusinessException("WORKFLOW 任务未配置关联工作流（refId 或 params.workflowId）");
        }
        int timeout = context.timeoutSeconds(WorkflowApiClient.DEFAULT_TIMEOUT_SECONDS);
        String requestId = context.scheduleKey();
        String bizDate = context.bizDate();

        RunAcceptDto accept;
        try {
            accept = workflowApiClient.triggerWithRequest(wfId, context.params(), "SCHEDULE",
                    job.getId(), jobLog.getId(), requestId, bizDate, jobLog.getScheduledAt());
        } catch (RestClientException | IllegalStateException e) {
            WorkflowRunDto existing = workflowApiClient.findByScheduleLog(wfId, jobLog.getId());
            if (existing == null || StrUtil.isBlank(existing.getId())) {
                throw new NoRetryException("无法确认工作流是否已受理: scheduleLogId=" + jobLog.getId()
                        + "，bizDate=" + bizDate + "，error=" + e.getMessage());
            }
            // 若查到的是上一次已结束的失败运行，当前提交结果仍然不确定。
            if (WorkflowApiClient.isFinal(existing.getRunStatus())) {
                throw new NoRetryException("查询到失败或停止的工作流: runId=" + existing.getId()
                        + "，本次提交结果未确认");
            }
            accept = RunAcceptDto.duplicate(existing.getId(), bizDate);
        }
        if (accept == null) {
            throw new NoRetryException("工作流受理无响应: bizDate=" + bizDate + "，requestId=" + requestId);
        }
        if (RunAcceptDto.STATE_REJECTED.equals(accept.getState())) {
            if ("RUN_ALREADY_ACTIVE".equals(accept.getRejectCode())) {
                throw new NoRetryException("业务日已有活跃运行: bizDate=" + bizDate
                        + "，activeRunId=" + accept.getActiveRunId());
            }
            if ("REQUEST_CONFLICT".equals(accept.getRejectCode())) {
                throw new NoRetryException("同键请求参数冲突: requestId=" + requestId);
            }
            throw new BusinessException("工作流受理被拒绝: " + accept.getRejectCode()
                    + ": " + (accept.getRejectMessage() == null ? "-" : accept.getRejectMessage()));
        }
        String runId = accept.getRunId();
        if (StrUtil.isBlank(runId)) {
            throw new NoRetryException("工作流受理结果缺少 runId: bizDate=" + bizDate);
        }
        log.info("WORKFLOW 任务 {} 已触发工作流 {}，runId={}", job.getJobName(), wfId, runId);
        WorkflowRunDto run;
        try {
            run = workflowApiClient.waitForRun(runId, timeout);
        } catch (WorkflowApiClient.RunTimeoutException e) {
            throw new NoRetryException("工作流已受理但等待超时: runId=" + runId + "，" + e.getMessage());
        }
        if ("RECOVERY_REQUIRED".equals(run.getRunStatus())) {
            throw new NoRetryException("工作流执行结果需要核查: runId=" + runId);
        }
        if (!"SUCCESS".equals(run.getRunStatus())) {
            throw new BusinessException("工作流运行失败: runId=" + runId
                    + "，status=" + run.getRunStatus()
                    + "，error=" + (run.getErrorMessage() == null ? "-" : run.getErrorMessage()));
        }
        log.info("WORKFLOW 任务 {} 完成，runId={}", job.getJobName(), runId);
        return 1;
    }
}
