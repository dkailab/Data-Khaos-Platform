-- ============================================================
-- Data Khaos - 数据治理 演示数据
-- 场景：数据字典 + 数据标准 + 字段治理绑定（业务名/字典/敏感级）
-- 说明：
--   1. 底表（demo_dim_channel / demo_dim_category / demo_fact_order 等）
--     由 seed-demo.sql 建立；本脚本以 IF NOT EXISTS 兜底，保证底表一并存在。
--   2. 字典/标准为系统元数据（meta_dict_* / meta_standard），幂等 upsert。
-- 执行：docker exec -i dk-mysql mysql -uroot -proot123456 data_khaos < seed-governance.sql
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 0. 底表兜底（依赖 seed-demo.sql，此处防止单独执行时缺失）
-- ============================================================

CREATE TABLE IF NOT EXISTS demo_dim_channel (
    id      INT PRIMARY KEY,
    channel VARCHAR(50) COMMENT '渠道'
) ENGINE=InnoDB COMMENT='渠道维度(演示)';

CREATE TABLE IF NOT EXISTS demo_dim_category (
    id       INT PRIMARY KEY,
    category VARCHAR(50) COMMENT '类目'
) ENGINE=InnoDB COMMENT='类目维度(演示)';

CREATE TABLE IF NOT EXISTS demo_fact_order (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_date  DATE COMMENT '订单日期',
    region_id   INT COMMENT '区域ID',
    province    VARCHAR(50) COMMENT '省份',
    channel_id  INT COMMENT '渠道ID',
    category_id INT COMMENT '类目ID',
    amount      DECIMAL(12,2) COMMENT '订单金额',
    qty         INT COMMENT '数量',
    cost        DECIMAL(12,2) COMMENT '成本',
    profit      DECIMAL(12,2) COMMENT '利润'
) ENGINE=InnoDB COMMENT='订单事实(演示)';

-- ============================================================
-- 1. 数据字典 - 字典类型
-- ============================================================

INSERT INTO meta_dict_type (id, type_code, type_name, description, status, sort_order) VALUES
('dict_channel',   'CHANNEL',   '销售渠道',   '订单来源渠道，关联 demo_dim_channel',  1, 1),
('dict_category',  'CATEGORY',  '商品类目',   '商品所属类目，关联 demo_dim_category', 1, 2),
('dict_orderstat', 'ORDER_STATUS', '订单状态', '订单生命周期状态',                   1, 3),
('dict_sensitive', 'DATA_SENSITIVE', '敏感级别', '字段敏感级别分级',                 1, 4)
ON DUPLICATE KEY UPDATE type_name = VALUES(type_name), description = VALUES(description);

-- ============================================================
-- 2. 数据字典 - 字典项
-- ============================================================

-- 销售渠道
INSERT INTO meta_dict_item (id, type_id, item_code, item_name, item_value, status, sort_order) VALUES
('dict_item_ch1', 'dict_channel',   '1', '线上商城', '1', 1, 1),
('dict_item_ch2', 'dict_channel',   '2', '官网直营', '2', 1, 2),
('dict_item_ch3', 'dict_channel',   '3', '线下门店', '3', 1, 3),
('dict_item_ch4', 'dict_channel',   '4', '分销代理', '4', 1, 4)
ON DUPLICATE KEY UPDATE item_name = VALUES(item_name);

-- 商品类目
INSERT INTO meta_dict_item (id, type_id, item_code, item_name, item_value, status, sort_order) VALUES
('dict_item_ca1', 'dict_category',  '1', '数码', '1', 1, 1),
('dict_item_ca2', 'dict_category',  '2', '家电', '2', 1, 2),
('dict_item_ca3', 'dict_category',  '3', '服饰', '3', 1, 3),
('dict_item_ca4', 'dict_category',  '4', '食品', '4', 1, 4),
('dict_item_ca5', 'dict_category',  '5', '美妆', '5', 1, 5),
('dict_item_ca6', 'dict_category',  '6', '家居', '6', 1, 6)
ON DUPLICATE KEY UPDATE item_name = VALUES(item_name);

-- 订单状态
INSERT INTO meta_dict_item (id, type_id, item_code, item_name, item_value, status, sort_order) VALUES
('dict_item_os1', 'dict_orderstat', 'NEW',       '待支付', '0', 1, 1),
('dict_item_os2', 'dict_orderstat', 'PAID',      '已支付', '1', 1, 2),
('dict_item_os3', 'dict_orderstat', 'SHIPPED',   '已发货', '2', 1, 3),
('dict_item_os4', 'dict_orderstat', 'COMPLETED', '已完成', '3', 1, 4),
('dict_item_os5', 'dict_orderstat', 'CANCELED',  '已取消', '4', 1, 5),
('dict_item_os6', 'dict_orderstat', 'REFUNDED',  '已退款', '5', 1, 6)
ON DUPLICATE KEY UPDATE item_name = VALUES(item_name);

-- 敏感级别
INSERT INTO meta_dict_item (id, type_id, item_code, item_name, item_value, status, sort_order) VALUES
('dict_item_se1', 'dict_sensitive', '0', '普通',     '0', 1, 1),
('dict_item_se2', 'dict_sensitive', '1', '敏感',     '1', 1, 2),
('dict_item_se3', 'dict_sensitive', '2', '高度敏感', '2', 1, 3)
ON DUPLICATE KEY UPDATE item_name = VALUES(item_name);

-- ============================================================
-- 3. 数据标准（供字段落标校验）
-- ============================================================

INSERT INTO meta_standard (id, std_code, std_name, category, data_type, data_length, data_precision,
                          data_scale, unit, enum_range, format_rule, description, status, sort_order) VALUES
('std_amount', 'STD_MONEY_AMOUNT', '金额标准', '格式类', 'DECIMAL', NULL, 12, 2, '元',
 NULL, '^[0-9]+(\\.[0-9]{1,2})?$', '订单金额/成本/利润类字段统一精度 DECIMAL(12,2)', 1, 1),
('std_qty',    'STD_QTY_INT',      '数量标准', '格式类', 'INT',     11, NULL, NULL, NULL,
 NULL, '^[0-9]+$', '订单数量类字段为整数', 1, 2),
('std_channel','STD_DICT_CHANNEL', '渠道编码标准', '编码类', 'VARCHAR', 50, NULL, NULL, NULL,
 '1:线上商城,2:官网直营,3:线下门店,4:分销代理', '^[1-4]$', '渠道编码需对应数据字典 CHANNEL', 1, 3),
('std_pcode',  'STD_DICT_CATEGORY','类目编码标准', '编码类', 'VARCHAR', 50, NULL, NULL, NULL,
 '1:数码,2:家电,3:服饰,4:食品,5:美妆,6:家居', '^[1-6]$', '类目编码需对应数据字典 CATEGORY', 1, 4)
ON DUPLICATE KEY UPDATE std_name = VALUES(std_name), category = VALUES(category),
                         data_type = VALUES(data_type), data_length = VALUES(data_length),
                         data_precision = VALUES(data_precision), data_scale = VALUES(data_scale),
                         enum_range = VALUES(enum_range), format_rule = VALUES(format_rule);

-- ============================================================
-- 4. 字段治理绑定（示例：为底表关键字段打业务名/字典/敏感级）
--    注：meta_column 记录由元数据采集（sync）产生，取决于数据源是否已同步。
-- ============================================================

UPDATE meta_column mc
JOIN meta_table mt ON mt.id = mc.table_id
JOIN (SELECT tc.column_name,
             tc.table_name,
             CASE
                 WHEN tc.column_name IN ('amount','cost','profit') THEN 'STD_MONEY_AMOUNT'
                 WHEN tc.column_name IN ('qty')                  THEN 'STD_QTY_INT'
                 WHEN tc.column_name IN ('channel_id')            THEN 'STD_DICT_CHANNEL'
                 WHEN tc.column_name IN ('category_id')           THEN 'STD_DICT_CATEGORY'
                 ELSE NULL
             END AS std_code
      FROM (
          SELECT 'demo_fact_order' AS table_name, 'amount' AS column_name UNION ALL
          SELECT 'demo_fact_order', 'cost'      UNION ALL
          SELECT 'demo_fact_order', 'profit'    UNION ALL
          SELECT 'demo_fact_order', 'qty'       UNION ALL
          SELECT 'demo_fact_order', 'channel_id' UNION ALL
          SELECT 'demo_fact_order', 'category_id'
      ) tc) cc ON cc.table_name = mt.table_name AND cc.column_name = mc.column_name
SET mc.biz_name = NULL,
    mc.description = CASE mc.column_name
        WHEN 'amount' THEN '订单金额（实付）'
        WHEN 'cost'  THEN '订单成本'
        WHEN 'profit'THEN '订单利润'
        WHEN 'qty'   THEN '订单数量'
        ELSE mc.description END,
    mc.sensitive_level = CASE mc.column_name
        WHEN 'amount' THEN 1 WHEN 'profit' THEN 1 WHEN 'qty' THEN 1 ELSE mc.sensitive_level END,
    mc.dict_type_code = CASE mc.column_name
        WHEN 'channel_id'  THEN 'CHANNEL'
        WHEN 'category_id' THEN 'CATEGORY'
        ELSE mc.dict_type_code END,
    mc.dict_type_name = CASE mc.column_name
        WHEN 'channel_id'  THEN '销售渠道'
        WHEN 'category_id' THEN '商品类目'
        ELSE mc.dict_type_name END
WHERE mt.table_name = 'demo_fact_order';

SET FOREIGN_KEY_CHECKS = 1;