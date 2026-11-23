USE data_khaos;

CREATE TABLE IF NOT EXISTS workflow_revision (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    wf_id VARCHAR(32) NOT NULL,
    project_id VARCHAR(32),
    revision_number INT NOT NULL,
    note VARCHAR(500) NOT NULL,
    created_by VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL,
    node_count INT NOT NULL,
    edge_count INT NOT NULL,
    graph_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_workflow_revision_number (wf_id, revision_number)
) ENGINE=InnoDB;
