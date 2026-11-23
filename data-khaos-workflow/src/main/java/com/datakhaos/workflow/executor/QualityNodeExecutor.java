package com.datakhaos.workflow.executor;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.dquality.api.model.DqSnapshotDto;
import com.datakhaos.dquality.api.service.QualityApiClient;
import com.datakhaos.workflow.entity.WorkflowNodeRun;
import com.datakhaos.workflow.mapper.WorkflowNodeRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 质量稽核节点执行器：触发质量任务并阻塞等待结论，作为下游发布节点的门禁。
 *
 * <p>节点配置（configJson）：{@code {"qualityTaskId": "...", "gate": "BLOCK"}}。</p>
 * <ul>
 *   <li>gate=BLOCK（默认）：结论为 FAIL/ERROR 时节点失败，后继节点被跳过（不发布下游资产）；</li>
 *   <li>gate=WARN：仅记录告警，节点仍算成功（用于旁路稽核）。</li>
 * </ul>
 * <p>成功后把快照ID 回写到节点运行记录的 refType/refId，形成可追溯的质量证据。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class QualityNodeExecutor implements NodeExecutor {

    /** 节点配置键：质量任务ID */
    public static final String CONFIG_QUALITY_TASK_ID = "qualityTaskId";

    /** 节点配置键：门禁动作 BLOCK（默认，阻断）/ WARN（仅告警） */
    public static final String CONFIG_GATE = "gate";

    /** 门禁动作：阻断下游 */
    public static final String GATE_BLOCK = "BLOCK";

    /** 门禁动作：仅告警 */
    public static final String GATE_WARN = "WARN";

    /** 结论：通过 */
    private static final String CONCLUSION_PASS = "PASS";

    private final QualityApiClient qualityApiClient;
    private final WorkflowNodeRunMapper nodeRunMapper;

    @Override
    public String getType() {
        return NodeType.QUALITY;
    }

    @Override
    public ExecResult execute(NodeContext context) throws Exception {
        Object taskIdObj = context.getConfig() == null ? null : context.getConfig().get(CONFIG_QUALITY_TASK_ID);
        String taskId = taskIdObj == null ? null : String.valueOf(taskIdObj).trim();
        if (StrUtil.isBlank(taskId)) {
            throw new IllegalArgumentException("QUALITY 节点未配置 " + CONFIG_QUALITY_TASK_ID);
        }

        Object gateObj = context.getConfig().get(CONFIG_GATE);
        String gate = gateObj == null || StrUtil.isBlank(String.valueOf(gateObj))
                ? GATE_BLOCK : String.valueOf(gateObj).trim().toUpperCase();

        long start = System.currentTimeMillis();
        DqSnapshotDto snapshot = qualityApiClient.audit(
                taskId, context.getRunId(), context.getNodeRunId(), context.getBizDate());
        if (snapshot == null || StrUtil.isBlank(snapshot.getId())) {
            throw new IllegalStateException("质量稽核无返回快照: qualityTaskId=" + taskId);
        }

        writeRef(context.getNodeRunId(), snapshot.getId());

        String conclusion = snapshot.getConclusion() == null ? "UNKNOWN" : snapshot.getConclusion();
        long cost = System.currentTimeMillis() - start;
        String execLog = String.format("质量稽核完成，结论 %s，评分 %s，通过 %s/%s，快照 %s，耗时 %dms",
                conclusion, snapshot.getScore(), snapshot.getRulePass(),
                snapshot.getRuleTotal(), snapshot.getId(), cost);

        if (CONCLUSION_PASS.equals(conclusion)) {
            return ExecResult.builder().success(true).log(execLog).build();
        }

        String detail = String.format("质量门禁未通过: 结论 %s，错误码 %s，快照 %s",
                conclusion, snapshot.getErrorCode() == null ? "-" : snapshot.getErrorCode(), snapshot.getId());
        if (GATE_WARN.equals(gate)) {
            log.warn("QUALITY 节点 gate=WARN，不阻断下游: {}", detail);
            return ExecResult.builder().success(true).log(execLog + "；" + detail).build();
        }
        throw new IllegalStateException(detail);
    }

    /** 回写节点运行记录与质量快照的关联 */
    private void writeRef(String nodeRunId, String snapshotId) {
        if (StrUtil.isBlank(nodeRunId)) {
            return;
        }
        try {
            WorkflowNodeRun patch = new WorkflowNodeRun();
            patch.setId(nodeRunId);
            patch.setRefType(NodeType.QUALITY);
            patch.setRefId(snapshotId);
            nodeRunMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("回写节点关联ID失败: nodeRunId={}, snapshotId={}, error={}", nodeRunId, snapshotId, e.getMessage());
        }
    }
}
