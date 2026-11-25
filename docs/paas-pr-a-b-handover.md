# PaaS PR A / PR B 交接

> 对应 `docs/paas-implementation-plan.md`「数仓数据服务 API · 下一阶段开发计划」的 PR A 与 PR B。
> 维护者 dkailab。基线 `e99b5d9`（数仓数据 API、版本发布和机器调用凭据已交付）。

---

## PR 与范围

**PR A · 真实调用验收** 与 **PR B · 数据服务工作台** 同一批次交付（计划允许并行）。

- PR A：新增可重复执行的订单 ADS 与数据 API 联调脚本，覆盖发布、读数、凭据吊销、API 下线与失败路径。
- PR B：新增「数据服务 → 数仓 API」工作台，项目内完成草稿 → 发布 → 签发 → 试跑 → 吊销。

**未改动**：后端零改动。PR A 只调用既有接口，PR B 只消费既有 HTTP 契约，故**本次无数据库迁移**。

---

## 入口与契约

### PR A · `scripts/verify-warehouse-api.sh`

| 项 | 值 |
| --- | --- |
| 入口 | `DK_PASSWORD=xxx bash scripts/verify-warehouse-api.sh` |
| 跳过登录 | `DK_TOKEN=<jwt>`（与 `DK_PASSWORD` 二选一） |
| 主要环境变量 | `DK_GATEWAY`(默认 `http://localhost:8099`)、`DK_PROJECT_ID`(`pg_001`)、`DK_USERNAME`(`lisi`)、`DK_BIZ_DATE`(`2026-11-17`)、`DK_ASSET_ID`(`mt_pr2_ads_region_gmv`)、`DK_API_CODE`(`verify-gmv-<epoch>`)、`DK_CROSS_PROJECT`(`pg_002`) |
| 退出码 | `0` 全部通过，`1` 存在失败断言，`2` 缺依赖或输入 |
| 自动清理 | `trap` 在正常与异常退出时吊销本次凭据、下线本次创建的 API 版本 |

覆盖的 13 项检查：

```
1  登录（captcha 需关闭）
2  受管物化分区就绪（publicationId + qualitySnapshotId 非空）
3  创建草稿（revision=1, DRAFT）
4  草稿阶段不可调用（期望 code=404）
5  发布（revision→2, PUBLISHED）
6  签发凭据（dkwh_ 前缀，返回明文）
7  机器调用 + 契约校验（发布证据非空、region 升序、gmv 两位小数、发布 ID 与就绪检查一致）
8  固定契约样例 /api/data/v1/order-region-gmv
9  伪造凭据被拒（code=401）
10 跨项目访问被拒（code=404/403）
11 吊销凭据 → 吊销后调用被拒（code=401）
12 下线 API → 下线后调用被拒（code=404）
13 收尾：凭据已回收
```

**状态码口径**：本项目 `GlobalExceptionHandler` 对 `BusinessException` 返回 `R.fail(code,msg)` 且 **HTTP 恒为 200**，业务码在响应体 `code`。因此脚本分别记录 `http=` 与 `code=`；仅网关层鉴权失败才会出现 HTTP 401/403。

### PR B · 数据服务工作台

| 项 | 值 |
| --- | --- |
| 页面路由 | `/service/warehouse-api`（`name: ServiceWarehouseApi`） |
| 导航入口 | 模块注册表 `service` 分类新增 `svc_warehouse`「数仓 API」 |
| 页面文件 | `data-khaos-web/src/views/service/WarehouseApiWorkbench.vue` |
| API 层 | `data-khaos-web/src/api/warehouseApi.ts` |
| 管理接口 | `/api/query/warehouse/v1/projects/{projectId}/apis`（沿用，未新增） |
| 运行接口 | `/api/data/v1/projects/{projectId}/apis/{apiCode}/versions/{version}?bizDate=` |

页面行为要点：

- 项目选择沿用 `route.query.project` + `listMyProjectWorkspaces()`，与 `ProjectWorkspace.vue` 一致；切换项目调用 `resetState()` 清空列表、详情、凭据、试跑结果与弹窗状态。
- 管理操作（新建/编辑/发布/下线/签发/吊销）按当前项目 `capabilities.includes('task:develop')` 显隐；普通成员仅可查看与试跑。**最终权限仍由后端 `requireProject` 判定**。
- 编辑、发布、下线均携带表格当前 `revision`；修订号过期时后端返回「版本状态或修订号已变化，请刷新」，由请求拦截器提示。
- 详情内提供试跑（返回发布 ID、质量快照 ID、记录表、截断状态）、请求示例（运行地址 + curl）与凭据列表。
- 凭据明文只在签发成功时展示，`@closed` 触发 `closeIssue()` 立即释放明文与表单状态。
- 状态映射：`DRAFT`/`PUBLISHED`/`OFFLINE` 用 `el-tag`（`DkStatus` 仅有 success/running/failed/queued/blocked，语义不匹配）。

---

## 数据库

**无新增表、字段或约束，无需迁移。** PR A 与 PR B 均建立在既有基础上：

- `warehouse_api_version`（含 `revision`、`status`、`max_rows`）
- `warehouse_api_credential`（含 `token_digest`、`expires_at`、`revoked_at`）
- PR 2B/2C/3 既有：`dk_materialization_version`、质量快照、资产证据

前提数据（已存在）：`db/migrate-paas-pr3.sql` 中的 `mt_pr2_ads_region_gmv` 资产与血缘；`db/seed-project-group.sql` 中的 `pg_001` 项目组与 `lisi`（零售-高级开发者，含 `task:develop`）。

---

## 实测

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 脚本语法 | `bash -n scripts/verify-warehouse-api.sh` | 通过 |
| 失败路径：缺输入 | `bash scripts/verify-warehouse-api.sh`（无密码/令牌） | `EXIT=2`，提示「缺少 DK_PASSWORD；或用 DK_TOKEN」 |
| 失败路径：网关不可达 | `DK_GATEWAY=http://127.0.0.1:1 DK_TOKEN=dummy bash scripts/verify-warehouse-api.sh` | 13 项全部走完并逐项标 `[FAIL]`，`EXIT=1`，汇总输出失败明细与未覆盖场景 |
| 前端类型 | `cd data-khaos-web && ./node_modules/.bin/vue-tsc --noEmit` | 0 错误 |
| 前端构建 | `cd data-khaos-web && npm run build` | 成功，产出 `WarehouseApiWorkbench-BfZvMUys.js` 16.88 kB |

执行中发现并修复的问题：

1. 收尾检查「凭据已回收」在网关不可达时误判为 `PASS`（`active=?` 未参与判定）→ 改为要求 `code=0 && active=0`。
2. 工作台初始用 `DkButton variant="ghost"`，该组件只支持 `primary/secondary/quiet` → 改为 `quiet`。
3. 模板内 curl 示例使用 `\"` 转义，Vue 不解析 → 改由 script 常量 `CURL_PLACEHOLDER` 提供。

---

## 未验证项

**PR A 的核心验收尚未在真实环境执行。** 本次未启动网关、query、permission、metadata、datasource 与 MySQL/Redis，因此「真实 MySQL/Redis 上跑通发布、读数、吊销、下线」这一完成条件**未达成**，脚本仅验证了自身逻辑与失败路径。

复验步骤：

```bash
# 1. 拉起依赖（MySQL/Redis 与各服务）
cd docker && docker compose up -d --build

# 2. 执行种子（按顺序）
#    mysql-init.sql → migrate-* → seed-project-group.sql → seed-paas-pr1.sql → migrate-paas-pr3.sql

# 3. 关闭自动化环境的验证码（否则登录必失败）
#    data-khaos-auth 配置或环境变量：data-khaos.captcha.enabled=false

# 4. 确认业务日已有质量通过的受管物化发布（REPLACE_BY_BIZ_DATE + MySQL 数据源）

# 5. 执行
DK_PASSWORD=<lisi 密码> bash scripts/verify-warehouse-api.sh
```

其余未覆盖场景（脚本末尾已打印，需造数）：

- 已发布空分区：目标日零行发布应返回空数组并保留发布元数据
- 质量失败保留上一成功分区：DWS 提交后注入金额差异，ADS 不应产出
- 目标版本标记不一致：篡改 `dk_materialization_version` 后应报「物化版本尚未就绪」
- 缺少资产权限：无该资产 `SELECT` 的成员调用应 403

另：目标表若配置行列策略，受管物化查询按设计返回 403（`MaterializationQueryService.verifyPublication`）。`seed-project-group.sql` 已插入 `sys_row_policy` / `sys_column_policy` 样例，联调前需确认 `pr2_ads_region_gmv` 未被策略覆盖。

---

## 下个 PR

**PR C · 应用注册、配额和可查审计**（`permission`、`query`、`gateway`），以 PR A 脚本为回归基线。

- 调用入口：以 `scripts/verify-warehouse-api.sh` 全绿为前置。
- 新增契约：`appId` 项目内稳定身份；授权唯一键 `appId + apiVersionId`（带过期时间与配额）；凭据支持多枚并存以滚动轮换；超额返回 HTTP `429`；审计返回 `requestId`、`appId`、API 版本、结果码、耗时、`publicationId`、调用时间。
- 示例 ID 参考：项目 `pg_001`，资产 `mt_pr2_ads_region_gmv`，业务日 `2026-11-17`。
- 迁移：需新增应用、授权、配额与调用审计表，**PR C 自带迁移脚本**；已有「签发人委派凭据」保留至过期或吊销，不迁移。

**PR D** 依赖 `examples/realtime-orders`（已存在），需先确定实时结果更新时间来源与时效语义，再接入发布管理。
