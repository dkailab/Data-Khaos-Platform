-- ============================================================
-- PaaS PRC 迁移：数仓 API 应用注册、授权、配额与调用审计
-- 目标：把「签发人委派凭据」升级为「应用稳定身份」——
--       应用(app) → 授权(grant, appId + apiVersionId) → 凭据(credential, 可多枚轮换)
--       并按 appId + apiVersionId 做跨实例共享配额，记录可检索的调用审计。
-- 执行：docker exec -i dk-mysql mysql -uroot -p data_khaos < db/migrate-warehouse-api-apps.sql
-- 幂等性：建表与种子用 IF NOT EXISTS / ON DUPLICATE KEY UPDATE 可重复执行；
--        为已存在的 warehouse_api_credential 增加 app_id 列属一次性变更，
--        重复执行会报 Duplicate column（与 db/migrate-dashboard-canvas.sql 的约定一致）。
-- 依赖：migrate-warehouse-api-versions.sql、migrate-warehouse-api-credentials.sql 已执行。
-- ============================================================

USE data_khaos;

-- ============================================================
-- 1) 应用注册：项目内稳定身份
--    appId 落在主键 id 上，是调用链与审计里长期不变的标识；
--    运行主体 runtime_subject 是 permission 侧的账号，数据范围由它独立的表/行/列权限决定。
-- ============================================================
CREATE TABLE IF NOT EXISTS warehouse_api_app (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    project_id VARCHAR(32) NOT NULL,
    app_name VARCHAR(100) NOT NULL,
    owner VARCHAR(32) NOT NULL,
    runtime_subject VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    revision INT NOT NULL,
    created_by VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL,
    updated_by VARCHAR(32) NOT NULL,
    update_time DATETIME NOT NULL,
    CONSTRAINT uk_warehouse_api_app_name UNIQUE (project_id, app_name),
    INDEX idx_warehouse_api_app_project (project_id, status, id)
) ENGINE=InnoDB;

-- ============================================================
-- 2) 授权：以 appId + apiVersionId 为唯一键
--    quota_per_minute 为该授权每自然分钟的调用上限；
--    expires_at 为空表示不设过期；revoked_at 非空表示已回收，后续请求在查询前拒绝。
-- ============================================================
CREATE TABLE IF NOT EXISTS warehouse_api_grant (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    app_id VARCHAR(32) NOT NULL,
    api_version_id VARCHAR(32) NOT NULL,
    quota_per_minute INT NOT NULL,
    expires_at DATETIME NULL,
    revoked_by VARCHAR(32) NULL,
    revoked_at DATETIME NULL,
    created_by VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL,
    updated_by VARCHAR(32) NOT NULL,
    update_time DATETIME NOT NULL,
    CONSTRAINT uk_warehouse_api_grant UNIQUE (app_id, api_version_id),
    INDEX idx_warehouse_api_grant_version (api_version_id, revoked_at)
) ENGINE=InnoDB;

-- ============================================================
-- 3) 配额窗口：按分钟固定窗口计数，落在共享库上，因此多实例天然共享同一份额度。
--    window_start 为 epoch 分钟数；used_count 由原子 UPDATE 递增。
-- ============================================================
CREATE TABLE IF NOT EXISTS warehouse_api_quota_window (
    id VARCHAR(96) NOT NULL PRIMARY KEY,
    app_id VARCHAR(32) NOT NULL,
    api_version_id VARCHAR(32) NOT NULL,
    window_start BIGINT NOT NULL,
    used_count INT NOT NULL,
    CONSTRAINT uk_warehouse_api_quota_window UNIQUE (app_id, api_version_id, window_start)
) ENGINE=InnoDB;

-- ============================================================
-- 4) 调用审计：只留调用元数据，凭据与原始业务结果留在调用链之外
--    requestId 供按次追溯授权结果、API 版本与数据发布证据。
-- ============================================================
CREATE TABLE IF NOT EXISTS warehouse_api_call_audit (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(32) NOT NULL,
    app_id VARCHAR(32) NULL,
    credential_id VARCHAR(32) NULL,
    api_version_id VARCHAR(32) NULL,
    api_code VARCHAR(48) NULL,
    api_version INT NULL,
    biz_date VARCHAR(32) NULL,
    result_code INT NOT NULL,
    cost_ms BIGINT NOT NULL,
    publication_id VARCHAR(64) NULL,
    quality_snapshot_id VARCHAR(64) NULL,
    called_at DATETIME NOT NULL,
    CONSTRAINT uk_warehouse_api_audit_request UNIQUE (request_id),
    INDEX idx_warehouse_api_audit_project (project_id, called_at, id),
    INDEX idx_warehouse_api_audit_app (app_id, called_at, id)
) ENGINE=InnoDB;

-- ============================================================
-- 5) 凭据归属应用
--    存量签发人委派凭据 app_id 为空，继续按签发人身份执行，直到过期或被吊销（PR A 脚本回归覆盖）。
--    此列属一次性变更：全新库由本脚本补齐，已升级库重复执行会提示列已存在，可忽略。
-- ============================================================
ALTER TABLE warehouse_api_credential ADD COLUMN app_id VARCHAR(32) NULL;

CREATE INDEX idx_warehouse_api_credential_app ON warehouse_api_credential (app_id, issued_at, id);
