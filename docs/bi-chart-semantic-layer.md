# BI 图表画布语义层改造方案

> 目的：把「仪表板 → 图表画布」从当前基于**数据集扁平字段**的绘制方式，升级为基于**数据集市语义层（模型 → 指标/维度）**的绘制方式，对齐 QuickBI / FineBI / Tableau 的语义层设计。
>
> 本文档是一份**可直接交给执行 AI 的实现规格**：包含现状诊断、目标架构、分阶段改动点、接口字段定义、SQL 生成规则与验收标准。实现时请严格沿用仓库现有代码风格（MyBatis-Plus + Lombok + Spring Boot；前端 Vue3 + TS + Element Plus），不要自行发明新的技术栈。

---

## 1. 现状与问题诊断

### 1.1 已有资产（勿重复造轮子）

仓库已经有一套**完整的指标维度语义层**，位于 `data-khaos-mart` 模块 + 前端 `src/views/mart`，能力如下：

| 概念 | 实体 / 文件 | 关键字段 |
|---|---|---|
| 模型 | `MartModel.java` | `modelName`、`modelType(STAR/SNOWFLAKE)`、`datasourceId`、`layerId` |
| 指标 | `MartMetric.java` | `metricName`、`metricCode`、`metricType(ATOMIC/DERIVED)`、`expression`、`unit`、`categoryId`、`modelId` |
| 维度 | `MartDimension.java` | `dimName`、`dimCode`、`dimType(COMMON/TIME/ORG)`、`sourceTable`、`sourceColumn`、`modelId` |
| 维度层级 | `MartDimLevel.java` | `dimId`、`levelName`、`levelColumn`、`levelOrder` |
| 模型关联 | `MartModelRel.java` | `modelId`、`factTable`、`dimTable`、`joinKey`、`joinType` |
| 后端服务 | `MartService.java` / `MartController.java` | 已含模型/指标/维度/关联/层级/预览 CRUD |
| 前端 API | `src/api/mart.ts` | 已封装全部接口 |

### 1.2 当前画布的实现方式

`data-khaos-visual` 的 `DatasetService` + 前端 `src/views/visual/dashboard/ChartBuilder.vue` 已经实现了一版画布，但它走的是一条**绕开指标体系的旁路**：

- 资产池数据源是 `VisualDataset`（SQL 数据集），把字段分类为 `DIMENSION` / `METRIC`。
- 指标 = 一个裸字段 + 下拉选 `SUM/AVG/COUNT...`（见 `DatasetService.aggSql`）。
- 维度 = 一个裸列，无类型、无层级。
- JOIN 不存在；跨数据集用 `datasetId` 硬判冲突（见 `ChartBuilder.vue` 的 `primaryDatasetId` / `conflictFields`）。
- 后端 `queryChart` 把数据集 SQL 当子查询 `FROM (数据集SQL) t` 包一层聚合。

### 1.3 根因

`VisualDataset` 实体已经预留了 **MODEL 模式**（`datasetType` 字段注释写明 `SQL / MODEL`，且有 `modelId` 字段），但：

1. `DatasetService.extractFieldsFromModel(modelId)` 是 **TODO 空实现**（`return new ArrayList<>()`），模型模式未被真正打通。
2. `ChartBuilder.vue` 只消费了 SQL 模式下 `listPublished()` 返回的平铺字段，从未消费模型语义。

**结论**：不是缺框架，而是框架（mart）与画布（visual）之间缺一条「语义查询」的桥梁。本次改造就是补这条桥梁。

---

## 2. 目标架构

```
仪表板(多Tab) ──► 图表画布 ChartBuilder ──► 语义查询接口 ──► 生成 SQL ──► Doris/Hive/MySQL
                        │
        ┌───────────────┼────────────────┐
     资产池(模型树)   货架(筛选/维度/指标)  样式
        │
   模型 MartModel(已发布 status=1)
     ├─ 指标 MartMetric（expression + unit + ATOMIC/DERIVED）
     └─ 维度 MartDimension（dimType + sourceTable/Column + 层级 MartDimLevel）
           └─ 关联 MartModelRel（factTable JOIN dimTable ON joinKey）
```

核心原则：

1. **指标自带聚合语义**：不在前端让用户选 aggType，`metric.expression` 即聚合表达式。
2. **维度自带类型与下钻**：`TIME` 维度支持年/月/日粒度；有 `MartDimLevel` 的维度支持层级下钻。
3. **JOIN 来自模型关联**：同模型内指标 + 维度通过 `MartModelRel` 自动 join 事实表；跨模型（`modelId` 不同）才视为不可联查。
4. **兼容 SQL 数据集**：保留现有「SQL 数据集」作为兜底资产分组，不删除现有能力。

---

## 3. 数据模型缺口（阶段 0 前置）

**缺失字段：** `MartModel` 没有「主事实表」，事实表目前只存在 `MartModelRel.factTable` 中，语义查询无法稳定定位 FROM 表。

- 表结构：`ALTER TABLE mart_model ADD COLUMN fact_table VARCHAR(128) NULL COMMENT '主事实表' AFTER datasource_id;`
- 实体：`MartModel.java` 增加 `private String factTable;`
- 前端类型：`src/types/index.ts` 的 `MartModel` 增加 `factTable?: string`
- DTO 透传：`MartService.toModelDto` 增加 `dto.setFactTable(model.getFactTable())`（若 `ModelDto` 有该字段则补上）。
- 建模界面：`src/views/mart/model/ModelList.vue`（或对话框）增加「主事实表」输入项。

---

## 4. 分阶段实施

> 每个阶段可独立验证，建议按 0 → 1 → 2 → 3 → 4 顺序执行。

### 阶段 0：模型补充主事实表（后端 + 前端小改）

**目标**：模型能稳定定位 FROM 表。

- 后端：`MartModel` 实体 + `toModelDto` + 建表 DDL（见第 3 节）。
- 前端：`MartModel` 类型 + 建模表单 + `MarketModelDto`（可选，市场卡片也可展示事实表）。
- 验收：`GET /api/mart/model/{id}` 返回的数据含 `factTable`；db 里 `mart_model.fact_table` 有值。

---

### 阶段 1：语义查询引擎（后端核心，新增于 data-khaos-mart）

**目标**：新增 `POST /api/mart/query`，输入通用语义查询请求，返回结果 + 生成 SQL。

#### 1.1 新增 DTO（放 `data-khaos-mart/src/main/java/com/datakhaos/mart/dto/`）

`MartQueryRequest.java`：

```java
@Data
public class MartQueryRequest implements Serializable {
    private String modelId;               // 必填
    private List<MetricRef> metrics;      // 至少一个
    private List<DimRef> dimensions;      // 可空
    private List<FilterRef> filters;      // 可空
    private List<SortRef> sorts;          // 可空
    private Integer limit;                 // 默认 1000，上限 10000

    @Data public static class MetricRef { private String metricCode; }   // 不需要 aggType
    @Data public static class DimRef {
        private String dimCode;
        private String grain;             // TIME 维度：Y / M / D（可选）
        private String levelColumn;        // 下钻层级列（可选，见阶段 3）
    }
    @Data public static class FilterRef {
        private String dimCode;            // 或 metricCode
        private String operator;           // EQ/NE/GT/GTE/LT/LTE/LIKE/IN/NOT_IN/BETWEEN
        private List<String> values;
    }
    @Data public static class SortRef { private String code; private String direction; } // ASC/DESC
}
```

`MartQueryResult.java`：

```java
@Data
public class MartQueryResult implements Serializable {
    private String sql;
    private QueryResult result;           // com.datakhaos.datasource.api.model.QueryResult
    private boolean truncated;
    private int originalRowCount;
}
```

#### 1.2 新增 `MartQueryService.java`

方法签名：`public MartQueryResult query(MartQueryRequest req)`。

实现步骤：

1. **权限与模型校验**：复用 `MartService.currentAuth()` 思路，校验 `CAP_MODEL_BROWSE`（`PermissionConstants.CAP_MODEL_BROWSE`），并 `checkModelGroup`。模型必须 `status=1`（已发布）。
2. **加载语义对象**：
   - `martModel = modelMapper.selectById(modelId)`
   - 指标列表：`metricMapper.selectList(eq modelId, eq status=1)`
   - 维度列表：`dimensionMapper.selectList(eq modelId, eq status=1)`
   - 关联列表：`modelRelMapper.selectList(eq modelId)`
   - 组装成 `Map<metricCode, MartMetric>`、`Map<dimCode, MartDimension>` 做白名单。
3. **白名单校验**：请求里的每个 `metricCode` / `dimCode` 必须存在于上述 Map，否则抛 `BusinessException`（防注入，参照 `DatasetService.requireField`）。
4. **生成 SQL**：见第 5 节算法。
5. **执行**：`datasourceApiClient.executeRaw(model.getDatasourceId(), sql)`（`DatasourceApiClient` 已提供该方法）。
   返回结构参照 `DatasetService.queryChart` 组装 `MartQueryResult`。
6. **安全**：所有标识符（表名/列名/别名）经 `safeCol` 清洗；所有值经 `esc` 转义。可直接把 `DatasetService` 里的 `safeCol/esc/buildCondition/inClause/uniqueAlias/stripSemicolon` 这几个私有方法**抽取或复制**到 `MartQueryService`（或提为 common 工具类）。

#### 1.3 新增接口

`MartController.java` 增加：

```java
@PostMapping("/query")
public R<MartQueryResult> query(@RequestBody MartQueryRequest req) {
    return R.ok(martQueryService.query(req));
}
```

#### 1.4 验收

- 给定一个已发布模型（含 1 原子指标 + 1 时间维度 + 1 普通维度 + 1 关联），调用 `/api/mart/query` 能返回正确聚合结果与清晰可读 SQL。
- 非法 `metricCode`/`dimCode` 被拒绝（报「指标不在模型中」类错误，而非 SQL 异常）。

---

### 阶段 2：画布资产池切到「模型 + 指标/维度」（前端核心）

**目标**：改造 `ChartBuilder.vue` 左侧资产池，从「数据集字段」切换到「模型语义资产」，并保留 SQL 数据集兜底。

#### 2.1 API 与类型

- `src/api/mart.ts` 增加：

```ts
export function queryMart(data: MartQueryRequest) {
  return post<MartQueryResult>('/mart/query', data)
}
```

- `src/types/index.ts` 增加 `MartQueryRequest` / `MartQueryResult` 等类型（与后端 DTO 对齐）。

#### 2.2 资产池改造

- 数据源：`pageMartMarket()`（只取 `status=1` 已发布模型，已按项目组隔离）。
- 展开一个模型 → 调 `martModelDetail(id)` 拿 `metrics[]` / `dimensions[]`，分「指标」「维度」两组渲染：
  - **指标条目**：`metricName` + `ATOMIC/DERIVED` 徽标 + `unit`；hover 显示 `expression`。
  - **维度条目**：`dimName` + `COMMON/TIME/ORG` 徽标；hover 显示 `sourceTable.sourceColumn`。
- **搜索高亮**：命中的指标/维度名或编码做高亮（沿用现有 `searchText` 逻辑，改作用在模型语义资产上）。
- **跨模型冲突判断基准**：把 `ChartBuilder.vue` 里所有 `datasetId` 相关判断改为 `modelId`。同一模型内指标 + 维度可联查；不同模型字段加入货架时高亮红色 + 禁用。
- **兜底**：把现有 `listPublishedDatasets()` 的数据集折叠成一个独立的「SQL 数据集」分组，放在模型树下方，逻辑基本复用现有 `addField`（仅对 SQL 数据集生效）。

#### 2.3 货架与查询

- 指标 chip：**去掉 aggType 下拉**，直接显示 `metricName` + `unit`。
- 维度 chip：`TIME` 维度附加「粒度」下拉（年/月/日）；有层级的维度后续接阶段 3 下钻。
- `runQuery` 改为调用 `queryMart`，入参用 `metricCode` / `dimCode`（不带 aggType）。

#### 2.4 验收

- 左侧能看到已发布模型，展开后分「指标/维度」；搜索能高亮命中项。
- 从同一模型拖入 1 指标 + 1 维度能出图；从不同模型拖入第二个字段时红色高亮且查询自动忽略。

---

### 阶段 3：筛选器语义化 + 维度下钻（前端 + 轻量后端）

**目标**：把「筛选器」做成真正可用的控件，并支持维度层级下钻。

#### 3.1 筛选器按类型分派

- **TIME 维度** → `el-date-picker`（日期范围 / 单日切换），生成 `FilterRef{operator:'BETWEEN', values:[start,end]}`。
- **枚举维度（COMMON/ORG）** → `el-select` 多选，选项来自新增枚举接口。
- **数值指标** → 区间输入（`>`/`<`/`BETWEEN`）。

#### 3.2 新增维枚举值接口（后端 mart）

`MartController.java` 增加：

```java
@GetMapping("/dimension/{dimId}/values")
public R<List<String>> dimensionValues(@PathVariable String dimId,
                                       @RequestParam(defaultValue = "100") int limit) {
    return R.ok(martQueryService.dimensionValues(dimId, limit));
}
```

实现：根据 `MartDimension.sourceTable + sourceColumn`，拼 `SELECT DISTINCT sourceColumn FROM sourceTable LIMIT n`，走 `executeRaw`。（维度表若在 `MartModelRel.dimTable` 中则直接取之；否则用 `sourceTable`。）

#### 3.3 维度层级下钻

- 维度有 `MartDimLevel` 时，维度 chip 上显示下钻箭头。
- 下钻 = 把 `DimRef.levelColumn` 从父级切换到下一级 `levelColumn`，重新查询（复用现有 `DrillRequest`/下钻 UI 思路）。
- 第一阶段可先做「层级切换」而非逐级钻取，降低复杂度。

#### 3.4 验收

- 时间维度筛选器渲染为日期控件；枚举维度渲染为下拉多选（有真实枚举值）。
- 有层级的维度可切换到下一层级并重新出图。

---

### 阶段 4：dataConfig 统一与仪表板回显（前端/后端收口）

**目标**：让新语义配置能正确保存进仪表板组件，并在编辑器/预览中回显。

#### 4.1 dataConfig 结构升级

保存时的 `dataConfig` JSON 结构从「字段语义」升级为「模型语义」：

```jsonc
{
  "mode": "MODEL",              // MODEL | SQL
  "modelId": "m_1001",
  "metrics": [ { "metricCode": "order_amount" } ],
  "dimensions": [ { "dimCode": "order_date", "grain": "M" } ],
  "filters": [ { "dimCode": "region", "operator": "IN", "values": ["华东","华北"] } ],
  "sorts": [ { "code": "order_amount", "direction": "DESC" } ],
  "limit": 1000
}
```

SQL 数据集仍用旧 `datasetId` + `fields` 结构，用 `mode` 区分。

#### 4.2 回显与渲染

- `ChartBuilder.vue` 的 `init()` 里按 `dataConfig.mode` 分派：`MODEL` 恢复模型/指标/维度；`SQL` 走旧逻辑。
- `DashboardEditor.vue` 与 `ChartRenderer.vue` 需能读 `mode=MODEL` 的 `dataConfig` 做渲染（若 `ChartRenderer` 目前只认字段语义，则给它加一个「模型语义 → 结果集」的兼容分支，或让画布保存时同时写入可渲染的 `querySql`，仪表板直接跑 `querySql` 兜底渲染）。

> 兜底策略（降低本阶段风险）：画布保存时，无论 MODEL 还是 SQL，都把**最终生成的 `querySql`** 写入组件（`VisualDashboardItem.querySql`）。这样仪表板预览可以继续用现有「执行 SQL」链路渲染，无需改造 `ChartRenderer` 的语义理解。MODEL 语义信息仅用于再次进入画布时回显编辑。

#### 4.3 验收

- 画布用模型语义做的图，保存后仪表板能正常渲染。
- 再次进入该组件的画布，能正确回显模型、指标、维度、筛选。

---

## 5. 核心 SQL 生成算法（阶段 1 的核心）

### 5.1 已知上下文

- `factTable`（来自 `MartModel.factTable`，阶段 0 补上；缺省时回退取第一条 `MartModelRel.factTable`）。
- 指标 `expression`（如 `SUM(order_amount)`）。
- 维度 `sourceTable` / `sourceColumn`。
- 关联 `MartModelRel{dimTable, joinKey, joinType}`。

### 5.2 原子指标（ATOMIC）

直接取 `metric.expression` 作为 SELECT 聚合列，并 `AS metricCode`：

```
SUM(f.order_amount) AS order_amount
```

### 5.3 派生指标（DERIVED，两段式）

派生指标的 `expression` 引用指标编码（如 `order_amount / order_cnt`）。生成两段式 SQL：

内层先把原子指标聚合出来，外层再算派生：

```sql
SELECT region_name AS region, order_amount / order_cnt AS avg_amount
FROM (
  SELECT r.region_name,
         SUM(f.order_amount) AS order_amount,
         COUNT(f.order_id)   AS order_cnt
  FROM dws_sales_order_fact f
  LEFT JOIN dim_region r ON f.region_id = r.region_id
  WHERE r.region_name IN ('华东','华北')
  GROUP BY r.region_name
) t
ORDER BY region_name ASC
LIMIT 1000
```

> 第一版只支持「原子指标 + 四则运算 / 括号」的 DERIVED 表达式（`expression` 直接引用原子指标编码）。更复杂的函数/跨指标嵌套列为后续迭代，文档标注为 TODO。

### 5.4 时间维度粒度（方言分派）

按 `datasourceId` 对应的数据源类型分派（`DatasourceApiClient.datasourceType` 已提供）：

| 数据源 | 年 | 月 | 日 |
|---|---|---|---|
| MySQL / Doris / DM / Oracle | `DATE_FORMAT(col,'%Y')` | `DATE_FORMAT(col,'%Y-%m')` | `DATE_FORMAT(col,'%Y-%m-%d')` |
| Hive | `date_format(col,'yyyy')` | `date_format(col,'yyyy-MM')` | `date_format(col,'yyyy-MM-dd')` |
| ClickHouse | `toString(toYear(col))` | `formatDateTime(col,'%Y-%m')` | `formatDateTime(col,'%Y-%m-%d')` |

时间粒度表达式在 SELECT 与 GROUP BY 里都要用，并 `AS dimCode`（有 grain 时 `dimCode` 后接 `_M` 等后缀避免与源列重名）。

### 5.5 JOIN 生成

- 遍历所选维度，对每个 `dim.sourceTable`（或经 `MartModelRel` 映射到的 `dimTable`）生成一次 `LEFT JOIN`。
- ON 条件：`factAlias.joinKey = dimAlias.joinKey`（`joinKey` 是事实表侧字段名；若维度表主键不同需约定 —— 第一版约定 `dimTable` 主键 = `factTable` 的 `joinKey` 同名列）。
- 事实表统一别名 `f`，维度表别名 `d1`、`d2`...；所有投影列带表别名前缀，避免歧义。

### 5.6 筛选 / 排序 / LIMIT

- 筛选：复用 `buildCondition`，但列用 `dim.sourceColumn`（或带表别名前缀）+ 值转义。
- 排序：仅允许投影列（维度列或指标别名）。
- LIMIT：默认 1000，上限 10000。

### 5.7 完整示例

输入：

```json
{
  "modelId": "m_1001",
  "metrics": [ { "metricCode": "order_amount" }, { "metricCode": "order_cnt" } ],
  "dimensions": [ { "dimCode": "region" }, { "dimCode": "order_date", "grain": "M" } ],
  "filters": [ { "dimCode": "region", "operator": "IN", "values": ["华东", "华北"] } ],
  "sorts": [ { "code": "order_amount", "direction": "DESC" } ],
  "limit": 500
}
```

输出 SQL（MySQL/Doris 方言，假设模型 `m_1001` 的 factTable=`dws_sales_order_fact`，region 维度 sourceTable=`dim_region`、sourceColumn=`region_name`，关联 `f.region_id = d1.region_id`）：

```sql
SELECT d1.region_name AS region,
       DATE_FORMAT(f.order_date, '%Y-%m') AS order_date_M,
       SUM(f.order_amount) AS order_amount,
       COUNT(f.order_id) AS order_cnt
FROM dws_sales_order_fact f
LEFT JOIN dim_region d1 ON f.region_id = d1.region_id
WHERE d1.region_name IN ('华东', '华北')
GROUP BY d1.region_name, DATE_FORMAT(f.order_date, '%Y-%m')
ORDER BY order_amount DESC
LIMIT 500
```

---

## 6. 关键文件改动清单

| 阶段 | 文件 | 动作 |
|---|---|---|
| 0 | `mart` 里 `MartModel.java` | 加 `factTable` |
| 0 | `mart/api/model/ModelDto.java` | 加 `factTable`（若存在） |
| 0 | `src/types/index.ts` `MartModel` | 加 `factTable?: string` |
| 0 | `src/views/mart/model/ModelList.vue` | 表单加主事实表 |
| 1 | `mart` 新增 `dto/MartQueryRequest.java`、`dto/MartQueryResult.java` | 新增 |
| 1 | `mart` 新增 `service/MartQueryService.java` | 新增语义查询 |
| 1 | `mart/controller/MartController.java` | 加 `/query`、`/dimension/{dimId}/values` |
| 2 | `src/api/mart.ts` | 加 `queryMart` |
| 2 | `src/types/index.ts` | 加查询请求/响应类型 |
| 2 | `src/views/visual/dashboard/ChartBuilder.vue` | 资产池/货架/查询/冲突判断改造 |
| 3 | `ChartBuilder.vue` + `MartController` | 筛选器控件 + 枚举接口 + 下钻 |
| 4 | `ChartBuilder.vue` / `DashboardEditor.vue` / `ChartRenderer.vue` | dataConfig 结构 + 回显 |

---

## 7. 给执行 AI 的实现约定

1. **参照现有代码风格**：后端看 `DatasetService.queryChart` 的 SQL 拼接与安全清洗写法；前端看 `ChartBuilder.vue` 现有拖拽/货架/高亮逻辑，只改数据来源和查询入参，别推倒重写样式。
2. **安全第一**：所有标识符走 `safeCol`，所有值走 `esc`，所有 `metricCode/dimCode` 走模型白名单，禁止把请求字段直接拼进 SQL。
3. **权限**：语义查询接口必须校验 `CAP_MODEL_BROWSE` + 项目组隔离（参考 `MartService.currentAuth()`）。
4. **兼容不破坏**：SQL 数据集绘制路径保留，用 `dataConfig.mode`（`MODEL`/`SQL`）区分，避免影响现有已保存的 SQL 图表。
5. **方言**：涉及日期函数必须按数据源类型分派，禁止写死 MySQL 语法。
6. **验证**：每阶段用真实已发布模型（`mart_model status=1`）联调，后端接口优先用 `curl` 验证 SQL 与结果正确。