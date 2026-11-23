USE data_khaos;

-- Preserve project ownership as of submission. Historical runs remain unassigned
-- until an operator can reconcile them from execution-time evidence.
ALTER TABLE workflow_run
    ADD COLUMN project_group_id VARCHAR(32) NULL,
    ADD INDEX idx_wf_run_project_time (project_group_id, create_time, id);

ALTER TABLE pipeline_instance
    ADD COLUMN project_group_id VARCHAR(32) NULL,
    ADD INDEX idx_pi_project_time (project_group_id, create_time, id);
