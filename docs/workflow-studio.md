# 数据开发工作台（调度编排）实现说明

> 对应页面：`/jobs/offline/edit/:id`（项目内）与 `/workflow/edit/:id`（独立入口）。
> 后端：`data-khaos-workflow`。迁移：`db/migrate-workflow-studio.sql`。

## 1. 目标与范围

把原来的「简易节点画布」升级为数据开发工作台，覆盖参考产品里的主线能力：

- 左侧资源树（SQL 文件、任务流）+ 可拖拽节点面板；
- 中间缩放画布、节点拖拽、端口连线、自动布局、依赖解析；
- 右侧任务设置：基本信息 / 任务配置 / 任务参数 / 依赖；
- SQL 任务支持「自定义 SQL」与「外部任务（引用已保存 SQL 文件）」；
- 任务级超时、重试、跳过、执行器组、独立调度、前置条件；
- 整图运行、运行当前及下游、单节点调试、任务列表、运行记录；
- 发布（自动版本快照并绑定版本号）、下线、版本管理；
- 跨任务流上游依赖：依赖策略、参数映射、依赖图、运行前门禁。

不改变既有契约：`workflow_def / workflow_node / workflow_edge / workflow_run / workflow_node_run`
仍按原语义工作；新增字段全部有默认值，存量任务流无需改造即可打开。

## 2. 数据模型

迁移脚本 `db/migrate-workflow-studio.sql`：

### 2.1 `workflow_node` 新增列

| 列 | 类型 | 说明 |
|---|---|---|
| `description` | VARCHAR(500) | 节点描述 |
| `skip_flag` | TINYINT DEFAULT 0 | 跳过执行；跳过仍释放下游 |
| `executor_group` | VARCHAR(64) | 执行器组标识（当前单执行器组，仅分组） |
| `independent_schedule` | TINYINT DEFAULT 0 | 是否允许独立调度（标识位） |
| `precondition` | VARCHAR(1000) | 前置条件表达式，空表示始终执行 |
| `node_params` | TEXT | 节点级参数 JSON，覆盖工作流参数 |

### 2.2 `workflow_def` 新增列

| 列 | 类型 | 说明 |
|---|---|---|
| `published_revision` | INT | 最近发布绑定的版本号 |
| `published_at` | DATETIME | 最近发布时间；下线置空 |

状态沿用：`0=停用/下线`、`1=启用`、`2=已发布`。定时调度读取 `status in (1,2)`。

### 2.3 `workflow_sql_file`

SQL 文件资源，草稿与已发布快照分离：

- `draft_content / draft_version`：编辑中的内容，每次保存版本号 +1；
- `published_content / published_version`：发布快照；`published_version=0` 表示未发布；
- `folder` 以 `/` 开头，默认 `/SQL文件`；
- 列表接口不返回正文（避免 500 条 × 1MB 的大响应），编辑器/预览按 id 读取详情。

### 2.4 `workflow_upstream`

跨任务流依赖：

- `depend_strategy`：`STRICT`（同业务日成功）/ `LOOSE`（最近成功即可）/ `OFF`（仅展示）；
- `param_mapping`：JSON，`{"本地参数":"上游参数"}`；
- `enabled`：是否参与门禁。

## 3. 节点类型与执行语义

| 类型 | 执行器 | 配置（configJson） |
|---|---|---|
| `SQL` | `SqlNodeExecutor` | `taskMode`（CUSTOM/EXTERNAL）、`datasourceId`、`sql`、`sqlSplitMode`（NONE/SPLIT）、`externalObject`（DRAFT/LATEST）、`sqlFileId` |
| `SHELL` | `ShellNodeExecutor` | `script` |
| `PYTHON` | `PythonNodeExecutor` | `script` |
| `DATA_OP` | `DataOpNodeExecutor` | 同 SQL |
| `PIPELINE` | `PipelineNodeExecutor` | `pipelineTaskId`，等待管道实例终态 |
| `QUALITY` | `QualityNodeExecutor` | `qualityTaskId`、`gate`（BLOCK/WARN） |
| `NESTED` | `NestedNodeExecutor` | `workflowId`，触发子任务流并轮询终态 |
| `DELAY` | `DelayNodeExecutor` | `delaySeconds`（0~86400） |

新增能力：

- **SQL 拆分执行**：`sqlSplitMode=SPLIT` 时按分号拆分（正确处理引号、反引号、行/块注释、转义），
  最多 200 条；任一语句失败立即中止并报告第几条。
- **外部 SQL**：`taskMode=EXTERNAL` 时读取 `workflow_sql_file`；`DRAFT` 读草稿，
  `LATEST` 读已发布快照（未发布过回退草稿）；节点未配置数据源时继承文件的数据源。
  来源说明写入节点执行日志。
- **节点参数**：`node_params` 合并到运行参数，覆盖工作流参数；`bizDate` 始终由运行上下文决定。
- **跳过**：`skip_flag=1` 不执行节点，节点状态记 `SKIPPED` 并释放下游。
- **前置条件**：支持 `${参数}`、`== = != > >= < <=`、`&& ||`、ISO 日期比较；
  兼容 `if [ 条件 ] then ... else ...` 片段。不满足时节点跳过并释放下游；
  表达式非法时节点失败（不静默放过）。
- **超时**：数据同步、子任务流、质量节点按节点超时中断等待；SQL 节点受数据源请求超时约束。
- **重试**：失败按 `retryCount + 1` 次、`retryInterval` 秒间隔重试，沿用原执行器逻辑。
- **嵌套幂等**：`NESTED` 节点用「父运行ID + 节点运行ID」作为子任务流请求键；
  节点重试时复用同一个子运行，不会重复触发。
- **保存校验与 UUID**：保存即校验 DAG（空图、重复编码、悬空连线、自依赖、环路）；
  节点按 `nodeCode` 复用旧行，节点 ID（工作台展示的 UUID）跨保存保持稳定。

## 4. 运行与发布

### 4.1 运行入口

- 整图运行：`POST /api/workflow/{id}/runs`，body `{requestId, bizDate, params}`；
- 运行当前及下游：同接口携带 `startCodes: ["n3"]`，只创建起始节点及其全部下游的节点运行实例；
- 单节点调试：`POST /api/workflow/{id}/nodes/{code}/debug`，同步执行、不落运行实例、不推进下游；
- 幂等：`requestId` 相同且参数一致返回 `DUPLICATE`；参数不一致返回 `REQUEST_CONFLICT`；
- 同 `(wfId, bizDate)` 只允许一条活跃运行（沿用 `workflow_bizdate_lock`）。

### 4.2 发布

`POST /api/workflow/{id}/publish`：

1. 校验 DAG（空图、重复节点、缺失端点、自依赖、环路）；
2. 自动创建版本快照（说明为「发布快照 时间」）；
3. 设置 `status=2`，写入 `published_revision / published_at`。

`POST /api/workflow/{id}/offline`：`status=0`，清空 `published_at`，保留历史版本。

## 5. 跨任务流依赖

- 保存：`PUT /api/workflow/{id}/upstreams`，整体替换；校验自依赖、重复、上游存在、环路；
- 列表：`GET /api/workflow/{id}/upstreams`；
- 依赖图：`GET /api/workflow/{id}/upstreams/graph?depth=3`，按层展开，最多 5 层；
- 运行门禁：`runByRequest / triggerWithRequest / triggerManual / 定时触发` 都会校验上游；
  未就绪时手动运行返回 `state=REJECTED, rejectCode=UPSTREAM_NOT_READY`；
- 参数注入：按 `param_mapping` 从上游最近一次成功运行（优先同业务日）读取 `triggerParams`，
  仅填充本次未显式传入的参数。

## 6. API 清单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/workflow/options?projectId=` | 任务流下拉选项；指定项目时按项目过滤 |
| POST | `/api/workflow/{id}/publish` | 发布 |
| POST | `/api/workflow/{id}/offline` | 下线 |
| GET | `/api/workflow/{id}/tasks` | 任务列表（节点 + 最近运行状态） |
| POST | `/api/workflow/{id}/nodes/{code}/debug` | 单节点调试 |
| GET/PUT | `/api/workflow/{id}/upstreams` | 上游依赖列表/整体保存 |
| GET | `/api/workflow/{id}/upstreams/graph` | 跨任务流依赖图 |
| GET | `/api/workflow/sql-file/list?projectId=&keyword=` | SQL 文件列表（不含正文） |
| GET | `/api/workflow/sql-file/{id}` | SQL 文件详情（含正文） |
| POST | `/api/workflow/sql-file` | 新建/更新 SQL 文件 |
| POST | `/api/workflow/sql-file/{id}/publish` | 发布 SQL 文件 |
| DELETE | `/api/workflow/sql-file/{id}` | 删除 SQL 文件 |

权限：读写都需要项目 `task:develop`；发布/下线还需要 `task:schedule`。
未归属项目的存量任务流跳过项目权限校验（与原逻辑一致）。

## 7. 前端结构

- `views/workflow/edit/WorkflowEdit.vue`：工作台骨架、画布、资源树、工具栏、对话框编排；
- `NodeSettings.vue`：右侧任务设置四个页签；
- `nodeCatalog.ts`：节点类型、默认配置、状态色；
- `SqlFilePicker.vue` / `SqlFileEditor.vue`：SQL 文件选择与编辑/发布；
- `WorkflowDependencyDialog.vue`：跨任务流依赖配置、批量策略、参数映射、依赖图；
- `RunWorkflowDialog.vue`：业务日期与运行参数；
- `NodeDebugDialog.vue`：单节点调试；
- `WorkflowTasksDialog.vue`：任务列表；
- `DependencyAnalysisDialog.vue`：依赖解析结果（起止节点、孤立节点、待补全配置）。

交互要点：

- 节点面板支持拖拽到画布，也支持点击添加；
- 从节点右侧圆点拖出连线，落到目标节点；
- 画布支持缩放、适应画布、按层级整理布局；
- `Delete/Backspace` 删除选中节点/依赖，`Ctrl/Cmd+S` 保存；
- 关闭/切换前有未保存提示。

## 8. 迁移与验证

```bash
# 1) 应用迁移（MySQL）
mysql -uroot -p data_khaos < db/migrate-workflow-studio.sql

# 2) 后端
/Users/dk/apache-maven/bin/mvn -o test

# 3) 前端
cd data-khaos-web
./node_modules/.bin/vue-tsc --noEmit
npx vite build --outDir /tmp/dk-web-verify

# 4) 真实链路验收（需要一个项目组长账号：task:develop + task:schedule）
DK_USERNAME=<组长账号> DK_PASSWORD=<密码> bash scripts/verify-workflow-studio.sh
```

`scripts/verify-workflow-studio.sh` 覆盖 44 项断言：SQL 文件草稿/发布/列表不返回正文、节点属性落库、
UUID 跨保存稳定、任务列表、单节点调试、整图运行、跳过/前置条件、嵌套任务流、运行当前及下游、
发布版本绑定、上游依赖门禁与依赖图、下线与清理。

手工验收：

1. 新建任务流，拖入 SQL / 脚本 / 数据同步 / 质量 / 延时 / 子任务流节点，连线保存；
2. SQL 节点自定义 SQL 保存后，用「保存为 SQL 文件」生成资源，再用另一节点「外部任务」引用，
   分别选择草稿/最新版本运行，确认日志里的来源说明；
3. 设置跳过、前置条件（如 `${bizDate} >= '2026-01-01'`）、超时、重试，运行后检查节点状态；
4. 选中中间节点「运行当前及下游」，确认只有该节点及下游产生节点运行记录；
5. 「解析依赖」检查起止节点与待补全配置；「调试」执行单节点；
6. 发布任务流，确认版本号与发布时间；下线后确认调度不再触发；
7. 配置上游任务流（STRICT/LOOSE/OFF）与参数映射，上游未成功时运行应返回 `UPSTREAM_NOT_READY`；
8. 「生成依赖图」按深度展开，确认方向为上游 → 当前任务流。

## 9. 已知边界

- 执行器组当前只做标识，平台仍按默认执行器组运行；
- 嵌套任务流在节点线程内阻塞等待；同一实例并发大量嵌套节点时需关注节点线程池容量（默认最大 16）；
- 节点超时对 SQL 节点不单独生效，受数据源请求超时约束；
- 未实现事件发生器、保存结果集、Flink 作业节点；
- 发布后再编辑会直接修改当前图（发布版本号仍指向旧快照），后续可引入「发布后编辑自动转草稿」；
- 上游依赖图最多展开 5 层，防止大图查询放大；
- SQL 文件正文上限 100 万字符，单文件列表最多 500 条。
