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

    /**
     * 远端恢复成功后，将本地的失败运行重新置为 RUNNING，准备续跑未执行的下游。
     * 只接受 FAILED / RECOVERY_REQUIRED，避免与仍在执行的运行并发。
     */
    @Transactional
    public boolean prepareResume(String runId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE workflow_run
                SET run_status = 'RUNNING', heartbeat_at = ?, end_time = NULL, duration_ms = 0,
                    error_message = NULL, recovery_code = NULL
                WHERE id = ? AND run_status IN ('FAILED', 'RECOVERY_REQUIRED')
                """, now, runId) == 1;
    }

    /** 仅重置失败快速跳过且从未开始的节点；配置跳过/前置条件跳过保留原状态。 */
    @Transactional
    public int resetSkippedNodes(String runId, List<String> nodeCodes, LocalDateTime now) {
        if (nodeCodes == null || nodeCodes.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(nodeCodes.size(), "?"));
        Object[] args = new Object[nodeCodes.size() + 1];
        args[0] = runId;
        for (int i = 0; i < nodeCodes.size(); i++) {
            args[i + 1] = nodeCodes.get(i);
        }
        return jdbc.update("""
                UPDATE workflow_node_run
                SET status = 'PENDING', start_time = NULL, end_time = NULL, duration_ms = 0,
                    error_message = NULL, log_text = NULL
                WHERE run_id = ? AND node_code IN (%s)
                  AND status = 'SKIPPED' AND end_time IS NULL
                  AND (log_text IS NULL OR log_text = '')
                """.formatted(placeholders), args);
    }

    /** 远端实例恢复成功后回写本地节点终态；耗时由调用方按节点开始时间计算。 */
    @Transactional
    public int markNodeRecovered(String nodeRunId, String logText, long durationMs, LocalDateTime now) {
        return jdbc.update("""
                UPDATE workflow_node_run
                SET status = 'SUCCESS', end_time = ?, duration_ms = ?,
                    log_text = ?, error_message = NULL
                WHERE id = ? AND status IN ('FAILED', 'RUNNING', 'PENDING', 'SUCCESS')
                """, now, Math.max(durationMs, 0), logText, nodeRunId);
    }

    /** 远端已成功但没有需要续跑的下游，直接收敛本地运行。 */
    @Transactional
    public void completeAfterRecovery(String runId, String reason, long duration, LocalDateTime now) {
        if (jdbc.update("""
                UPDATE workflow_run
                SET run_status = 'SUCCESS', error_message = ?, duration_ms = ?, end_time = ?, recovery_code = NULL
                WHERE id = ? AND run_status = 'RUNNING'
                """, reason, duration, now, runId) == 1) {
            release(runId);
        }
    }

    /** 远端已成功但本地下游续跑无法提交：保留待核查，避免误报成功。 */
    @Transactional
    public void failResume(String runId, String reason, LocalDateTime now) {
        jdbc.update("""
                UPDATE workflow_run
                SET run_status = 'RECOVERY_REQUIRED', error_message = ?, end_time = ?,
                    recovery_code = 'LOCAL_RESUME_FAILED'
                WHERE id = ? AND run_status = 'RUNNING'
                """, reason, now, runId);
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
