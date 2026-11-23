package com.datakhaos.permission.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectRunOperations {
    private final ProjectGroupAccess access;
    private final JdbcTemplate jdbc;

    public void acknowledge(String projectId, String runId) {
        String actor = requireOperator(projectId);
        if (jdbc.update("""
                UPDATE workflow_run
                SET alert_acknowledged_at = COALESCE(alert_acknowledged_at, CURRENT_TIMESTAMP),
                    alert_acknowledged_by = COALESCE(alert_acknowledged_by, ?)
                WHERE project_group_id = ? AND id = ? AND run_status IN ('FAILED', 'RECOVERY_REQUIRED')
                """, actor, projectId, runId) == 0 && jdbc.queryForObject("""
                SELECT COUNT(*) FROM workflow_run WHERE project_group_id = ? AND id = ?
                    AND run_status IN ('FAILED', 'RECOVERY_REQUIRED') AND alert_acknowledged_at IS NOT NULL
                """, Integer.class, projectId, runId) == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务告警不存在");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void resolve(String projectId, String runId, String confirmation) {
        String actor = requireOperator(projectId);
        String note = confirmation == null ? "" : confirmation.strip();
        if (note.isBlank() || note.length() > 500) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "请填写不超过 500 字的核查说明");
        }
        List<Recovery> records = jdbc.query("""
                SELECT run_status, recovery_resolved_at FROM workflow_run
                WHERE project_group_id = ? AND id = ? FOR UPDATE
                """, (rs, row) -> new Recovery(rs.getString(1), rs.getTimestamp(2) != null), projectId, runId);
        if (records.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "运行不存在或不可访问");
        }
        Recovery recovery = records.get(0);
        if (recovery.resolved()) return;
        if (!"RECOVERY_REQUIRED".equals(recovery.status())) {
            throw new BusinessException("当前运行无需恢复核查");
        }
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM pipeline_instance
                WHERE workflow_run_id = ? AND (status IS NULL OR status NOT IN (1, 2, 3))
                """, Integer.class, runId) != 0) {
            throw new BusinessException("仍有未结束的关联管道，业务日锁继续保留");
        }
        jdbc.update("""
                UPDATE workflow_run SET run_status = 'FAILED', recovery_resolved_at = CURRENT_TIMESTAMP,
                    recovery_resolved_by = ?, recovery_note = ?
                WHERE id = ? AND project_group_id = ?
                """, actor, note, runId, projectId);
        jdbc.update("""
                UPDATE workflow_node_run SET status = 'FAILED', end_time = COALESCE(end_time, CURRENT_TIMESTAMP)
                WHERE run_id = ? AND status = 'RUNNING'
                """, runId);
        jdbc.update("DELETE FROM workflow_bizdate_lock WHERE run_id = ?", runId);
    }

    private String requireOperator(String projectId) {
        var membership = access.requireMembership(projectId);
        if (membership.getCapabilityFlags() == null || !membership.getCapabilityFlags().contains("task:schedule")) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return access.actorId();
    }

    private record Recovery(String status, boolean resolved) {}
}
