-- ============================================================
-- PaaS PR3 迁移：运行证据、数据地图与 BI
-- 目标：把 PR2 的示例表登记进数据地图（可检索、有结构血缘），
--       沉淀 mart 语义指标（地区成交额），并生成 BI 仪表板。
-- 执行：docker exec -i dk-mysql mysql -uroot -p data_khaos < db/migrate-paas-pr3.sql
-- 幂等性：重复执行安全（DELETE + INSERT / ON DUPLICATE KEY UPDATE）。
-- 依赖：migrate-paas-pr2b.sql、migrate-paas-pr2c.sql、migrate-pipeline-publication.sql 已执行。
-- ============================================================

USE data_khaos;

-- ============================================================
-- 1) 数据地图：登记 PR2 示例表与结构血缘
-- ============================================================
INSERT INTO meta_database (id, datasource_id, database_name, description)
VALUES ('md_pr2_dw', 'ds_dw', 'data_khaos', 'PR2 示例-订单源库'),
       ('md_pr2_app', 'ds_app', 'data_khaos', 'PR2 示例-订单数仓（ODS/DWD/DWS/ADS）')
ON DUPLICATE KEY UPDATE database_name = VALUES(database_name), description = VALUES(description);

INSERT INTO meta_table (id, database_id, table_name, table_type, description)
VALUES
 ('mt_pr2_orders_src',     'md_pr2_dw', 'pr2_orders_src',     'TABLE', 'PR2 示例-源订单（业务库）'),
 ('mt_pr2_ods_orders',     'md_pr2_app', 'pr2_ods_orders',     'TABLE', 'PR2 示例-ODS 订单'),
 ('mt_pr2_dwd_orders',     'md_pr2_app', 'pr2_dwd_orders',     'TABLE', 'PR2 示例-DWD 支付订单明细'),
 ('mt_pr2_dws_region_daily','md_pr2_app', 'pr2_dws_region_daily','TABLE', 'PR2 示例-DWS 日地区汇总'),
 ('mt_pr2_ads_region_gmv', 'md_pr2_app', 'pr2_ads_region_gmv', 'TABLE', 'PR2 示例-ADS 地区成交额')
ON DUPLICATE KEY UPDATE database_id = VALUES(database_id), description = VALUES(description);

-- The gate evaluates the warehouse asset under its registered datasource identity.
UPDATE dquality_rule SET datasource_id = 'ds_app'
WHERE id IN ('dqr_pr2c_dws_nn_date', 'dqr_pr2c_dws_nn_region',
             'dqr_pr2c_dws_range_amount', 'dqr_pr2c_dws_recon');

-- 字段（供数据地图字段页展示）
DELETE FROM meta_column WHERE table_id IN
 ('mt_pr2_orders_src','mt_pr2_ods_orders','mt_pr2_dwd_orders','mt_pr2_dws_region_daily','mt_pr2_ads_region_gmv');
INSERT INTO meta_column (id, table_id, column_name, column_type, is_nullable, is_primary_key, description, sort_order, biz_name)
VALUES
 ('mc_src_1','mt_pr2_orders_src','order_id','VARCHAR',0,1,'订单号',1,'订单号'),
 ('mc_src_2','mt_pr2_orders_src','region','VARCHAR',0,0,'地区',2,'地区'),
 ('mc_src_3','mt_pr2_orders_src','amount','DECIMAL',0,0,'金额',3,'金额'),
 ('mc_src_4','mt_pr2_orders_src','status','VARCHAR',0,0,'状态 PAID/CANCEL',4,'状态'),
 ('mc_src_5','mt_pr2_orders_src','order_date','VARCHAR',0,0,'业务日期',5,'业务日期'),
 ('mc_ods_1','mt_pr2_ods_orders','order_id','VARCHAR',0,1,'订单号',1,'订单号'),
 ('mc_ods_2','mt_pr2_ods_orders','region','VARCHAR',0,0,'地区',2,'地区'),
 ('mc_ods_3','mt_pr2_ods_orders','amount','DECIMAL',0,0,'金额',3,'金额'),
 ('mc_ods_4','mt_pr2_ods_orders','status','VARCHAR',0,0,'状态',4,'状态'),
 ('mc_ods_5','mt_pr2_ods_orders','order_date','VARCHAR',0,0,'业务日期',5,'业务日期'),
 ('mc_dwd_1','mt_pr2_dwd_orders','order_id','VARCHAR',0,1,'订单号',1,'订单号'),
 ('mc_dwd_2','mt_pr2_dwd_orders','region','VARCHAR',0,0,'地区',2,'地区'),
 ('mc_dwd_3','mt_pr2_dwd_orders','amount','DECIMAL',0,0,'金额',3,'金额'),
 ('mc_dwd_4','mt_pr2_dwd_orders','order_date','VARCHAR',0,0,'业务日期',4,'业务日期'),
 ('mc_dws_1','mt_pr2_dws_region_daily','stat_date','VARCHAR',0,1,'业务日期',1,'业务日期'),
 ('mc_dws_2','mt_pr2_dws_region_daily','region','VARCHAR',0,1,'地区',2,'地区'),
 ('mc_dws_3','mt_pr2_dws_region_daily','order_cnt','INT',0,0,'订单数',3,'订单数'),
 ('mc_dws_4','mt_pr2_dws_region_daily','amount_sum','DECIMAL',0,0,'金额汇总',4,'金额汇总'),
 ('mc_ads_1','mt_pr2_ads_region_gmv','stat_date','VARCHAR',0,1,'业务日期',1,'业务日期'),
 ('mc_ads_2','mt_pr2_ads_region_gmv','region','VARCHAR',0,1,'地区',2,'地区'),
 ('mc_ads_3','mt_pr2_ads_region_gmv','gmv','DECIMAL',0,0,'成交额',3,'成交额');

-- 结构血缘：源订单 → ODS → DWD → DWS → ADS
DELETE FROM meta_table_lineage WHERE id LIKE 'tl_pr2_%';
INSERT INTO meta_table_lineage (id, source_table_id, target_table_id, source_column, target_column, relation_type)
VALUES
 ('tl_pr2_ods', 'mt_pr2_orders_src',      'mt_pr2_ods_orders',      NULL, NULL, 'ETL'),
 ('tl_pr2_dwd', 'mt_pr2_ods_orders',      'mt_pr2_dwd_orders',      NULL, NULL, 'ETL'),
 ('tl_pr2_dws', 'mt_pr2_dwd_orders',      'mt_pr2_dws_region_daily',NULL, NULL, 'ETL'),
 ('tl_pr2_ads', 'mt_pr2_dws_region_daily','mt_pr2_ads_region_gmv',  NULL, NULL, 'ETL');

-- ============================================================
-- 2) mart 语义指标：地区成交额（ADS 层）
-- ============================================================
INSERT INTO mart_model
    (id, project_group_id, layer_id, model_name, model_code, model_type, datasource_id, fact_table, description, status, version)
VALUES
 ('mm_pr2_region_gmv', NULL, 'layer_ads', 'PR2-地区成交额', 'PR2_REGION_GMV', 'STAR', 'ds_app', 'pr2_ads_region_gmv',
  'PR2 示例：ADS 地区成交额（按业务日、地区）', 1, 1)
ON DUPLICATE KEY UPDATE
    model_name = VALUES(model_name), fact_table = VALUES(fact_table),
    datasource_id = VALUES(datasource_id), layer_id = VALUES(layer_id),
    status = VALUES(status), update_time = NOW();

INSERT INTO mart_metric
    (id, project_group_id, metric_name, metric_code, metric_type, expression, data_type, unit, model_id, description, status)
VALUES
 ('mt_pr2_gmv', NULL, '地区成交额', 'region_gmv', 'ATOMIC', 'SUM(gmv)', 'DECIMAL', '元',
  'mm_pr2_region_gmv', '按业务日与地区汇总的成交额', 1)
ON DUPLICATE KEY UPDATE
    metric_name = VALUES(metric_name), expression = VALUES(expression),
    data_type = VALUES(data_type), unit = VALUES(unit), status = VALUES(status);

INSERT INTO mart_dimension
    (id, project_group_id, dim_name, dim_code, dim_type, model_id, source_table, source_column, description, status)
VALUES
 ('dim_pr2_region', NULL, '地区', 'pr2_region', 'COMMON', 'mm_pr2_region_gmv', 'pr2_ads_region_gmv', 'region', '地区维度', 1),
 ('dim_pr2_stat_date', NULL, '业务日期', 'stat_date', 'TIME', 'mm_pr2_region_gmv', 'pr2_ads_region_gmv', 'stat_date', '业务日期（年/月/日粒度）', 1)
ON DUPLICATE KEY UPDATE
    dim_name = VALUES(dim_name), dim_code = VALUES(dim_code), source_table = VALUES(source_table),
    source_column = VALUES(source_column), dim_type = VALUES(dim_type);

-- ============================================================
-- 3) BI 仪表板：PR2 订单地区成交额
--    从看板可下钻到 ADS 分区及其生产运行（query_sql 关联 pipeline_instance）
-- ============================================================
-- 看板相关列统一为 utf8mb4_general_ci，而连接默认 collation 是 utf8mb4_0900_ai_ci，
-- 显式指定避免 「Illegal mix of collations」。
SET @d_pr2 = _utf8mb4'dash_pr2_region_gmv' COLLATE utf8mb4_general_ci;
SET @b_pr2 = _utf8mb4'board_pr2_main' COLLATE utf8mb4_general_ci;
SET @ds_app = _utf8mb4'ds_app' COLLATE utf8mb4_general_ci;

DELETE FROM visual_dashboard_item WHERE dashboard_id = @d_pr2;
DELETE FROM visual_board WHERE dashboard_id = @d_pr2;
DELETE FROM visual_dashboard WHERE id = @d_pr2;

INSERT INTO visual_dashboard (id, name, description, layout, refresh_interval, status, version, create_by)
VALUES (@d_pr2, 'PR2 订单地区成交额', '订单数仓 ADS 层地区成交额，含分区发布运行追溯', NULL, 60, 2, 1, '1');

INSERT INTO visual_board (id, dashboard_id, board_name, icon, board_type, sort_order, status)
VALUES (@b_pr2, @d_pr2, '成交额', 'DataAnalysis', 'ANALYSIS', 0, 1);

-- 3.1 KPI：最近业务日 GMV 合计
INSERT INTO visual_dashboard_item
    (id, dashboard_id, board_id, title, chart_type, datasource_id, query_sql, drill_sql, config,
     pos_x, pos_y, width, height)
VALUES
 ('it_pr2_01', @d_pr2, @b_pr2, 'GMV 合计（最近业务日）', 'NUMBER', @ds_app,
  'SELECT ROUND(SUM(gmv), 2) AS gmv FROM pr2_ads_region_gmv WHERE stat_date = (SELECT MAX(stat_date) FROM pr2_ads_region_gmv)',
  NULL,
  '{"valueColumn":"gmv","unit":"元","thousand":true,"decimals":2}',
  0, 0, 300, 120);

-- 3.2 柱状图：最近业务日各地区 GMV
INSERT INTO visual_dashboard_item
    (id, dashboard_id, board_id, title, chart_type, datasource_id, query_sql, drill_sql, config,
     pos_x, pos_y, width, height)
VALUES
 ('it_pr2_02', @d_pr2, @b_pr2, '各地区 GMV（最近业务日）', 'BAR', @ds_app,
  'SELECT region, ROUND(gmv, 2) AS gmv FROM pr2_ads_region_gmv WHERE stat_date = (SELECT MAX(stat_date) FROM pr2_ads_region_gmv) ORDER BY gmv DESC',
  'SELECT stat_date, region, ROUND(gmv, 2) AS gmv FROM pr2_ads_region_gmv ORDER BY stat_date DESC, gmv DESC LIMIT 100',
  '{"xAxisColumn":"region","valueColumn":"gmv"}',
  320, 0, 620, 340);

-- 3.3 折线图：GMV 日趋势
INSERT INTO visual_dashboard_item
    (id, dashboard_id, board_id, title, chart_type, datasource_id, query_sql, drill_sql, config,
     pos_x, pos_y, width, height)
VALUES
 ('it_pr2_03', @d_pr2, @b_pr2, 'GMV 日趋势', 'LINE', @ds_app,
  'SELECT stat_date, ROUND(SUM(gmv), 2) AS gmv FROM pr2_ads_region_gmv GROUP BY stat_date ORDER BY stat_date',
  NULL,
  '{"xAxisColumn":"stat_date","valueColumn":"gmv"}',
  960, 0, 620, 340);

-- 3.4 明细表：ADS 分区 + 生产运行（运行证据入口）
INSERT INTO visual_dashboard_item
    (id, dashboard_id, board_id, title, chart_type, datasource_id, query_sql, drill_sql, config,
     pos_x, pos_y, width, height)
VALUES
 ('it_pr2_04', @d_pr2, @b_pr2, 'ADS 分区与生产运行', 'TABLE', @ds_app,
  'SELECT a.stat_date, a.region, ROUND(a.gmv, 2) AS gmv, p.id AS pipeline_instance_id, p.workflow_run_id, p.`rows` AS written_rows FROM pr2_ads_region_gmv a LEFT JOIN pipeline_partition_publication p ON p.target_datasource_id = ''ds_app'' AND p.target_database_name = ''data_khaos'' AND p.target_table = ''pr2_ads_region_gmv'' AND p.biz_date = a.stat_date COLLATE utf8mb4_general_ci ORDER BY a.stat_date DESC, a.gmv DESC',
  NULL,
  '{"publicationAssetId":"mt_pr2_ads_region_gmv"}',
  0, 360, 1580, 340);

-- 3.5 明细表：DWS 质量证据（门禁快照）
INSERT INTO visual_dashboard_item
    (id, dashboard_id, board_id, title, chart_type, datasource_id, query_sql, drill_sql, config,
     pos_x, pos_y, width, height)
VALUES
 ('it_pr2_05', @d_pr2, @b_pr2, 'DWS 质量门禁证据', 'TABLE', @ds_app,
  'SELECT s.biz_date, s.table_name, s.conclusion, s.error_code, s.score, s.rule_total, s.rule_pass, s.rule_fail, s.workflow_run_id, s.workflow_node_run_id FROM dquality_snapshot s JOIN workflow_run r ON r.id = s.workflow_run_id AND r.wf_id = ''wf_pr2c_orders'' WHERE s.datasource_id = ''ds_app'' AND s.database_name = ''data_khaos'' AND s.table_name = ''pr2_dws_region_daily'' ORDER BY s.create_time DESC, s.id DESC LIMIT 50',
  NULL,
  '{"publicationAssetId":"mt_pr2_ads_region_gmv","runEvidenceColumn":"workflow_run_id"}',
  0, 720, 1580, 300);
