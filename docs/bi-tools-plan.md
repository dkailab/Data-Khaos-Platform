# Data Khaos 数据 BI 工具规划

> 版本：v1.1.0（迭代规划）
> 定位：在 v1.0.0 指标分析闭环（明细 → 模型 → 指标 → 可视化）基础上，补齐**元数据治理**与**数据标准**能力，让 BI 工具具备企业级语义层竞争力。

---

## 1. 规划目标

1. **语义层自洽**：从"物理表/字段"到"业务指标/维度"之间建立可治理的语义映射，让业务人员看得懂、用得稳。
2. **数据可信**：字段级业务语义（业务名/说明/字典）+ 数据标准落标校验，先治标再治本。
3. **Open-Core 边界**：基础治理进社区版，安全合规/自动稽核进企业版（见 `open-core-plan.md`）。

---

## 2. BI 工具能力全景

```
┌─ 数据接入层 ────────────────────────────────────────────────┐
│ 数据源管理（SPI 连接器）→ 元数据自动采集                    │
├─ 元数据治理层 ──────────────────────────────────────────────┤
│ 结构/搜索/血缘 + 字段语义(业务名/说明) + 字典 + 数据标准     │
├─ 语义建模层 ────────────────────────────────────────────────┤
│ 集市模型(STAR/雪花) + 指标 + 维度(含层级下钻) + 模型关联    │
├─ 分析查询层 ────────────────────────────────────────────────┤
│ SQL 工作台 + 指标接口(MartQuery) + 行/列级数据权限          │
├─ 可视化分析层 ──────────────────────────────────────────────┤
│ 仪表板 + 分析板(拖拽/下钻/联动) + 即席分析 + 发布预览       │
└─ 调度推送层 ────────────────────────────────────────────────┘
```

---

## 3. 元数据层缺口盘点

对照企业级 BI（帆软/Tableau/Metabase）的元数据中心，现状缺口如下：

| # | 缺口 | 现状 | 本次建设 | 优先级 |
|---|------|------|---------|--------|
| D1 | 字段级业务语义缺失 | `meta_column` 仅有物理描述 | 新增 `bizName / bizComment / dictTypeCode / dictTypeName` | P0 |
| D2 | 数据字典管理 | 无 | 新增 `meta_dict_type / meta_dict_item` + CRUD | P0 |
| D3 | 数据标准落标校验 | 无 | 新增 `meta_standard` + 字段标准比对接口 | P0 |
| D4 | 元数据检索仅按物理名 | `search` 只匹配表名/字段名 | ✅ 检索范围扩展：字段业务名/说明/字典名称 | P1 |
| D5 | 表/字段血缘仅手动维护 | `meta_table_lineage` | ✅ 新增 `POST /api/meta/lineage/analyze` SQL 血缘自动分析（轻量正则解析） | P2 |
| D6 | 元数据采集覆盖易被治理覆盖 | 采集会覆盖 description | ✅ 采集保护：description/sensitiveLevel 仅未治理时回填，已治理不被覆盖 | P1 |
| D7 | 数据质量稽核 | 无 | 企业版规划（`dquality-requirements.md`） | P3 |

> **本次落地：D1、D2、D3、D4、D5、D6 全部完成**；D7 数据质量稽核留待企业版。

---

## 4. 元数据层建设（本次）

### 4.1 表结构

| 表 | 说明 | 关键字段 |
|----|------|---------|
| `meta_column`（扩展） | 字段信息 | + `biz_name` 业务名 / `biz_comment` 业务说明 / `dict_type_code` / `dict_type_name` |
| `meta_dict_type`（新增） | 字典类型 | `type_code`/`type_name`/`description`/`status` |
| `meta_dict_item`（新增） | 字典项 | `type_id`/`item_code`/`item_name`/`item_value` |
| `meta_standard`（新增） | 数据标准 | `std_code`/`std_name`/`category`/`data_type`/`data_length`/`enum_range`/`format_rule` |

### 4.2 后端接口

```
PUT  /api/meta/column/{id}            更新字段业务元数据（业务名/说明/字典/敏感级）
GET  /api/meta/column/{id}/standard-check  数据标准落标校验
POST /api/meta/lineage/analyze        SQL 血缘自动分析（解析 INSERT INTO/CREATE TABLE ... FROM/JOIN）
GET  /api/meta/search?keyword=        检索增强（表名/表注释/字段名/业务名/业务说明/字典名称）
GET  /api/meta/dict/type/*            字典类型 CRUD / 列表
GET  /api/meta/dict/item/*            字典项 CRUD / 按类型列表
GET  /api/meta/standard/*             数据标准 CRUD / 列表
```

### 4.3 落标校验逻辑

比对字段与标准：
- **类型**：`column_type` 前缀匹配 `standard.data_type`
- **长度**：`column_length <= standard.data_length`
- **枚举**：标准声明 `enum_range` 时，字段需关联字典（`dict_type_code` 非空）

---

## 5. 后续路线图

| 阶段 | 里程碑 | 内容 |
|------|--------|------|
| 1.0（本次） | 元数据治理基座 | 字段语义 + 字典 + 标准落标 + 检索增强 + SQL 血缘自动分析 + 采集保护 |
| 1.1 | 治理可视化 | 数据字典/标准管理界面（已随菜单进入「数据治理」）、元数据列表页（库→表→字段治理） |
| 2.0 | 数据质量稽核 | 完整性/唯一性/波动/空值率（企业版） |
| 3.0 | AI-Agent | 自然语言查询、指标诊断（企业版） |

---

## 6. 测试数据

### 6.1 脚本

| 脚本 | 内容 |
|------|------|
| `db/seed-demo.sql` | 演示底表：区域/渠道/类目维度 + 订单事实（含 30 天逐日） |
| `db/seed-governance.sql` | 治理测试数据：4 类字典 + 4 项数据标准 + demo 字段语义绑定 |

### 6.2 内置字典

- `CHANNEL` 销售渠道（线上商城/官网直营/线下门店/分销代理）
- `CATEGORY` 商品类目（数码/家电/服饰/食品/美妆/家居）
- `ORDER_STATUS` 订单状态（待支付→已退款）
- `DATA_SENSITIVE` 敏感级别（普通/敏感/高度敏感）

### 6.3 内置标准

- `STD_MONEY_AMOUNT` 金额 DECIMAL(12,2)
- `STD_QTY_INT` 数量 INT
- `STD_DICT_CHANNEL` 渠道编码枚举
- `STD_DICT_CATEGORY` 类目编码枚举

### 6.4 底表示例绑定

`demo_fact_order` 的 `amount/cost/profit/qty` 关联金额/数量标准并打敏感标记，`channel_id/category_id` 关联字典。

---

*Data Khaos © 2026 dkailab · Licensed under Apache 2.0*