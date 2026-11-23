package com.datakhaos.workflow.runner;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowRunLifecycle {
    private final JdbcTemplate jdbc;

    public boolean start(String runId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE workflow_run SET run_status = 'RUNNING', heartbeat_at = ?
                WHERE id = ? AND run_status = 'PENDING'
                """, now, runId) == 1;
    }

    public void heartbeat(String runId, LocalDateTime now) {
        jdbc.update("UPDATE workflow_run SET heartbeat_at = ? WHERE id = ? AND run_status = 'RUNNING'", now, runId);
    }

    @Transactional
    public boolean startNode(String runId, String nodeId, LocalDateTime now) {
        List<String> status = jdbc.queryForList("SELECT run_status FROM workflow_run WHERE id = ? FOR UPDATE",
                String.class, runId);
        if (!status.equals(List.of("RUNNING"))) return false;
        return jdbc.update("""
                UPDATE workflow_node_run SET status = 'RUNNING', start_time = ?
                WHERE id = ? AND run_id = ? AND status IN ('PENDING', 'RUNNING')
                """, now, nodeId, runId) == 1;
    }

    @Transactional
    public void rejectQueued(String runId, LocalDateTime now) {
        if (jdbc.update("""
                UPDATE workflow_run SET run_status = 'FAILED', end_time = ?, recovery_code = 'QUEUE_REJECTED',
                    error_message = '运行未执行，工作流队列无法受理'
                WHERE id = ? AND run_status = 'PENDING'
                """, now, runId) == 1) {
            skipPending(runId, now);
            release(runId);
        }
    }

    public List<String> expired(LocalDateTime cutoff) {
        return jdbc.queryForList("""
                SELECT id FROM workflow_run
                WHERE (run_status = 'PENDING' AND create_time < ?)
                   OR (run_status = 'RUNNING' AND COALESCE(heartbeat_at, start_time, create_time) < ?)
                ORDER BY create_time, id LIMIT 100
                """, String.class, cutoff, cutoff);
    }

    @Transactional
    public void expire(String runId, LocalDateTime cutoff, LocalDateTime now) {
        if (jdbc.update("""
                UPDATE workflow_run SET run_status = 'FAILED', end_time = ?, recovery_code = 'QUEUE_INTERRUPTED',
                    error_message = '排队期间中断，节点未开始执行'
                WHERE id = ? AND run_status = 'PENDING' AND create_time < ?
                  AND NOT EXISTS (SELECT 1 FROM workflow_node_run n WHERE n.run_id = ? AND n.status <> 'PENDING')
                """, now, runId, cutoff, runId) == 1) {
            skipPending(runId, now);
            release(runId);
            return;
        }
        if (jdbc.update("""
                UPDATE workflow_run SET run_status = 'RECOVERY_REQUIRED', end_time = ?, recovery_code = 'HEARTBEAT_EXPIRED',
                    error_message = '执行心跳已过期，需核查外部写入状态'
                WHERE id = ? AND (
                    (run_status = 'RUNNING' AND COALESCE(heartbeat_at, start_time, create_time) < ?)
                    OR (run_status = 'PENDING' AND create_time < ?))
                """, now, runId, cutoff, cutoff) == 1) {
            skipPending(runId, now);
        }
    }

    @Transactional
    public void finish(String runId, String status, String reason, long duration, boolean unconfirmed, LocalDateTime now) {
        if (jdbc.update("""
                UPDATE workflow_run
                SET run_status = ?, error_message = ?, duration_ms = ?, end_time = ?, recovery_code = ?
                WHERE id = ? AND run_status = 'RUNNING'
                """, status, reason, duration, now, unconfirmed ? "SUBMISSION_UNCONFIRMED" : null, runId) == 1
                && !unconfirmed) {
            release(runId);
        }
    }

    private void skipPending(String runId, LocalDateTime now) {
        jdbc.update("""
                UPDATE workflow_node_run SET status = 'SKIPPED', end_time = ?
                WHERE run_id = ? AND status = 'PENDING'
                """, now, runId);
    }

    private void release(String runId) {
        jdbc.update("DELETE FROM workflow_bizdate_lock WHERE run_id = ?", runId);
    }
}
