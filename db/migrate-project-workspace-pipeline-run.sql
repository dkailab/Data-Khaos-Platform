USE data_khaos;

ALTER TABLE pipeline_instance
    ADD COLUMN workspace_user_id VARCHAR(32) NULL,
    ADD COLUMN workspace_request_id VARCHAR(36) NULL,
    ADD COLUMN workspace_active_key CHAR(64) NULL,
    ADD UNIQUE KEY uk_pi_workspace_request (project_group_id, task_id, workspace_user_id, workspace_request_id),
    ADD UNIQUE KEY uk_pi_workspace_active (workspace_active_key);
