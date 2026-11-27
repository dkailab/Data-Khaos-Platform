package com.datakhaos.schedule.handler;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.approval.api.service.ApprovalApiClient;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.api.model.FlinkRunDto;
import com.datakhaos.workflow.api.service.WorkflowApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 实时作业任务：只提交已审批过的作业版本。
 *
 * <p>流作业不会自行结束，调度以 SUBMITTED 视为成功；同一计划时刻派生稳定 UUID，
 * 重复触发命中同一条运行记录。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlinkJobHandler implements JobHandler {

    private final WorkflowApiClient workflowApiClient;
    private final ApprovalApiClient approvalApiClient;

    @Override
    public String type() {
        return "FLINK";
    }

    @Override
    public int execute(JobContext context) {
        var job = context.job();
        String projectId = context.param("projectId");
        if (StrUtil.isBlank(projectId)) {
            throw new BusinessException("FLINK 任务未配置项目（params.projectId）");
        }
        String assetId = context.refOrParam("assetId");
        if (StrUtil.isBlank(assetId)) {
            throw new BusinessException("FLINK 任务未配置作业资产（refId 或 params.assetId）");
        }
        int version = parseVersion(context);
        try {
            if (approvalApiClient.findApproved(ApprovalApiClient.TYPE_FLINK_JOB, assetId, version).isEmpty()) {
                throw new BusinessException("该作业版本未取得审批，拒绝提交");
            }
        } catch (IllegalStateException e) {
            throw new NoRetryException("无法确认作业版本是否已审批: " + e.getMessage());
        }
        // 作业提交接口只收 UUID 作为幂等键：由「任务+计划时刻」派生 UUIDv5，
        // 既满足格式要求，又保证同一计划时刻重复触发命中同一条运行记录。
        String requestId = UUID.nameUUIDFromBytes(
                context.scheduleKey().getBytes(StandardCharsets.UTF_8)).toString();
        FlinkRunDto run;
        try {
            run = workflowApiClient.triggerFlink(projectId, assetId, version, requestId);
        } catch (RestClientException | IllegalStateException e) {
            throw new NoRetryException("无法确认实时作业是否已提交: " + e.getMessage());
        }
        if (run == null || StrUtil.isBlank(run.id())) {
            throw new NoRetryException("实时作业提交返回空运行记录");
        }
        String state = run.state() == null ? "" : run.state();
        if ("SUBMITTED".equals(state)) {
            log.info("FLINK 任务 {} 已提交实时作业 {} v{}，runId={}，engineJobId={}",
                    job.getJobName(), assetId, version, run.id(), run.engineJobId());
            return 1;
        }
        if ("FAILED".equals(state) || "CANCELED".equals(state)) {
            throw new BusinessException("实时作业提交失败: runId=" + run.id() + "，error="
                    + (run.message() == null ? "-" : run.message()));
        }
        throw new NoRetryException("实时作业提交结果待确认: runId=" + run.id() + "，state=" + state);
    }

    private int parseVersion(JobContext context) {
        String text = context.param("version");
        try {
            int version = Integer.parseInt(text == null ? "" : text.strip());
            if (version > 0) {
                return version;
            }
        } catch (NumberFormatException ignored) {
            // 统一走下面的错误提示
        }
        throw new BusinessException("FLINK 任务未配置有效的作业版本（params.version）");
    }
}
