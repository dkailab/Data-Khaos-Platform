# PR C · 应用注册、配额与可查审计 — 交付说明

对应 `docs/paas-implementation-plan.md` 的 PR C。本 PR 把「签发人委派凭据」升级为「应用稳定身份」：
应用（appId）→ 授权（appId + apiVersionId，带配额与过期）→ 凭据（可多枚并存轮换），
并让每次机器调用都可按 `requestId` 追溯。

---

## 1. 改动入口

### 迁移脚本（必须先执行）

```bash
docker exec -i dk-mysql mysql -uroot -p data_khaos < db/migrate-warehouse-api-apps.sql
```

新增四张表并为存量凭据表补列：

| 表 | 作用 | 关键约束 |
| --- | --- | --- |
| `warehouse_api_app` | 应用注册 | `uk_warehouse_api_app_name (project_id, app_name)` |
| `warehouse_api_grant` | 应用授权 | `uk_warehouse_api_grant (app_id, api_version_id)` |
| `warehouse_api_quota_window` | 分钟配额窗口 | `uk_warehouse_api_quota_window (app_id, api_version_id, window_start)` |
| `warehouse_api_call_audit` | 调用审计 | `uk_warehouse_api_audit_request (request_id)` |
| `warehouse_api_credential.app_id` | 凭据归属应用（可空） | 空值即存量「签发人委派」凭据 |

**迁移顺序**：`migrate-warehouse-api-versions.sql` → `migrate-warehouse-api-credentials.sql` → `migrate-warehouse-api-apps.sql`。
最后一条 `ALTER TABLE warehouse_api_credential ADD COLUMN app_id` 是一次性变更，重复执行会提示列已存在（与
`db/migrate-dashboard-canvas.sql` 的既有约定一致），可忽略。

### 后端（`data-khaos-query`）

| 文件 | 说明 |
| --- | --- |
| `entity/WarehouseApiApp*.java` `WarehouseApiGrant` `WarehouseApiQuotaWindow` `WarehouseApiCallAudit` | 四个新实体 |
| `mapper/WarehouseApi{App,Grant,QuotaWindow,CallAudit}Mapper.java` | 对应 Mapper |
| `service/WarehouseApiAppService.java` | 应用注册/启停/授权，运行主体校验 |
| `service/WarehouseApiQuotaService.java` | 按 appId+apiVersionId 的分钟固定窗口配额 |
| `service/WarehouseApiAuditService.java` | 审计写入与分页查询 |
| `service/WarehouseApiCredentialService.java` | 应用凭据签发/吊销，`invoke` 接入应用身份、配额与审计 |
| `controller/WarehouseApiAppController.java` | 应用与授权、应用凭据 |
| `controller/WarehouseApiAuditController.java` | 审计查询 |
| `controller/WarehouseApiController.java` | 调用端点改为 `ResponseEntity`，超额返回 HTTP 429 |
| `exception/QuotaExceededException.java` | 唯一需要改变 HTTP 状态码的业务异常 |

`data-khaos-common` 的 `ResultCode` 增加 `TOO_MANY_REQUESTS(429)`。

### 联调脚本

`scripts/verify-warehouse-apps.sh`（新增）—— 14 步闭环，失败返回非零并列出失败步骤。
`scripts/verify-warehouse-api.sh`（未改动）—— 作为旧凭据兼容的回归基线。

---

## 2. 接口样例

管理接口前缀 `/api/query/warehouse/v1/projects/{projectId}/apps`，鉴权沿用平台 JWT 与 `task:develop`。

```bash
# 注册应用（运行主体是 permission 侧的独立账号）
POST /apps
{"name":"settle-svc","runtimeSubject":"svc_settle"}

# 授权某 API 版本，配额 60 次/分钟，90 天后过期
POST /apps/{appId}/grants
{"apiCode":"region-gmv","version":1,"quotaPerMinute":60,"expiresAt":"2027-02-23T00:00:00Z"}

# 签发应用凭据（明文只返回一次；同一版本可再签一枚做滚动轮换）
POST /apps/{appId}/credentials/region-gmv/versions/1
{"ttlHours":24}

# 停用 / 启用（带当前 revision 做乐观锁）
POST /apps/{appId}/status?revision=1&enabled=false

# 回收授权
POST /apps/{appId}/grants/{grantId}/revoke
```

调用端点路径不变，凭据放在 `Authorization`：

```bash
curl -H "Authorization: Bearer dkwh_..." \
     -H "X-Request-Id: order-sync-20261125-001" \
     "https://<gateway>/api/data/v1/projects/pg_001/apis/region-gmv/versions/1?bizDate=2026-11-17"
```

成功时响应头带 `X-Request-Id`（未传则服务端生成），**超额时返回 HTTP 429** 并带 `Retry-After`：

```http
HTTP/1.1 429
Retry-After: 37
X-Request-Id: order-sync-20261125-001

{"code":429,"msg":"应用在本分钟内的配额已用尽（60 次/分钟）","data":null}
```

审计查询：

```bash
GET /api/query/warehouse/v1/projects/pg_001/audits?requestId=order-sync-20261125-001
GET /api/query/warehouse/v1/projects/pg_001/audits?appId=...&from=2026-11-01&to=2026-11-25
```

单条审计字段：`requestId / projectId / appId / credentialId / apiCode / apiVersion / apiVersionId /
bizDate / resultCode / costMs / publicationId / qualitySnapshotId / calledAt`。
凭据明文、摘要与业务结果行都不进审计表。

---

## 3. 调用链判定顺序

应用凭据的每次调用按以下顺序判定，任何一步不通过都**在查询执行前**返回，不会触达数据源：

1. 凭据有效（摘要匹配、未吊销、未过期）
2. 路径与凭据所属 API 版本一致
3. 应用存在且为 `ENABLED`
4. 授权存在、未回收、未过期
5. 运行主体在 permission 侧可用且属于该项目
6. 版本为 `PUBLISHED`
7. 按 `appId + apiVersionId` 占配额 → 超出即 429
8. 以运行主体身份执行查询

存量「签发人委派凭据」（`app_id` 为空）走原路径：以签发人身份执行、`task:develop` 校验、不占应用配额。

---

## 4. 实际执行的验收

### 自动化（已执行，全部通过）

```bash
# 全项目编译
/Users/dk/apache-maven/bin/mvn -o -q compile -DskipTests          # 通过

# 全项目测试
/Users/dk/apache-maven/bin/mvn -o test                            # BUILD SUCCESS
```

关键用例 `data-khaos-query/src/test/java/.../WarehouseApiAppQuotaTest.java`（4 个用例，H2 + MySQL 模式）：

| 用例 | 覆盖的计划验收点 |
| --- | --- |
| `quotasAreIndependentPerAppAndRotationKeepsTheAppQuota` | 两应用额度互不影响；轮换凭据不重置额度；**另建一套服务实例**（只共享数据库）共享同一份额度 |
| `disabledAppAndRevokedGrantAreRejectedBeforeAnyQueryRuns` | 停用应用、回收授权、运行主体停用，均在查询前拒绝，且用执行计数断言**查询根本没有发生** |
| `auditIsTraceableByRequestIdAndKeepsCredentialsOut` | 按 `requestId` 查到 appId、API 版本、`publicationId`、`qualitySnapshotId`；超额调用也留痕；按项目与日期分页；跨项目不可查；审计不含凭据与结果 |
| `rejectsRuntimeSubjectsThatCannotRunInTheProject` | 跨项目/内置管理员/停用/不存在的运行主体一律拒绝；未授权版本不发凭据 |

模块测试总数：query 25 项、gateway 2 项、permission 6 项、pipeline 7 项、workflow 35 项、notification 6 项，全部通过。

### 联调脚本（已编写，待真实环境执行）

```bash
DK_PASSWORD=<测试成员密码> bash scripts/verify-warehouse-apps.sh
```

已验证的失败路径：缺 `DK_PASSWORD` → 退出码 2；网关不可达 → 逐项 FAIL、打印失败明细与复现信息、退出码 1。
**真实环境尚未执行**（本轮未启动 MySQL、Redis 与服务），完成条件待补。

### 旧凭据兼容

`scripts/verify-warehouse-api.sh` 未做任何改动，其 13 项检查覆盖的签发人委派路径在
`WarehouseApiLifecycleTest` 中同样是 3 个用例原样通过——说明 `app_id` 为空的存量凭据行为未变。

---

## 5. 需要你知道的三项决策

### 5.1 限流放在 query 而非 gateway

计划把 `gateway` 也列进本 PR 的模块范围。实际未改网关，原因：

- 网关（WebFlux）拿不到 `appId`——必须先校验凭据摘要才能解析出应用，而凭据校验要查 query 库；
  在网关做等于把凭据表访问搬到网关，破坏现有分层。
- 计划本身写的是「真实身份和授权确认后按 `appId + apiVersionId` 限流」，确认身份就在 query 服务内。
- 跨实例共享通过数据库行实现，不需要引入 Redis。

**网关现状需要留意**：`application.yml` 的 `data-khaos-query` 路由挂了 `RequestRateLimiter`
（`replenishRate=10, burstCapacity=20`，`userKeyResolver` 取 `X-User-Id`，机器调用取不到就退化为客户端 IP）。
这意味着机器调用还有一层**按 IP 的 10 rps 粗粒度限流**在应用配额之前生效，且它的 429 不带业务码。
当授权配额接近或高于 10 次/秒时，会先撞到这一层——此时审计里不会留痕（请求没进到服务）。
调整配额时请把这一层算进去；若要让两者一致，需要单独开一张专用路由或提高该路由额度。

### 5.2 凭据按「应用 + API 版本」签发，不是按应用

`warehouse_api_credential.api_version_id` 是 `NOT NULL` 且参与唯一/索引，改造成可空会牵动存量数据与索引。
因此应用凭据的粒度是 `(appId, apiVersionId)`：同一对可并存多枚做滚动轮换，配额按同一对累计——
「轮换保留应用额度」的要求成立，且存量凭据零迁移风险。

代价是一个应用若被授权 3 个 API 版本，需要持有 3 枚凭据。若后续希望收敛为「一个应用一枚凭据」，
需把 `api_version_id` 放开为可空并调整 `authenticate`，属独立改造。

### 5.3 配额是「分钟固定窗口」，不是滑窗

实现是共享库上的 `(app_id, api_version_id, window_start)` 原子递增行：带条件 `UPDATE ... WHERE used < quota`
保证并发不超发，跨实例天然共享。窗口按自然分钟切，因此存在边界效应（59 秒与 61 秒的两个突发会各自满额）。
选它是因为无需新增 Redis 依赖、可在 H2 单测里真跑。若业务要求严格平滑，可换成令牌桶（需 Redis）。

窗口行保留 5 分钟，每 512 次调用顺带清理一次，避免堆积。

---

## 6. 待联调与遗留

1. **真实环境执行 `verify-warehouse-apps.sh`**（本 PR 的完成条件）。前置：关闭验证码、执行新迁移脚本、
   业务日已有质量通过的受管物化发布、目标表无行列策略。
2. **网关 429 与业务 429 的区分**：建议后续让网关在 429 时也返回 `R` 结构，或在响应头加来源标记，
   便于调用方判断是 IP 级粗粒度限流还是应用配额用尽。
3. **应用无删除接口**：当前只能停用（`DISABLED`）+ 回收授权。若需要真正下线，需补 `DELETE /apps/{appId}`
   与历史数据处理策略（审计里 `app_id` 是弱引用，删除后仍应按 appId 可查）。
4. **前端未做**：按计划 PR C 范围只含 `permission`/`query`/`gateway`，应用管理目前只有接口。
   `data-khaos-web/src/modules/registry.ts` 里 `svc_publish`（应用注册/凭据运营）仍是待建设状态，
   可作为 PR B 工作台的后续页签接入现有 `/apps` 契约。
5. **未做数据库级唯一性兜底**：`quota_window` 的递增依赖 `uk_warehouse_api_quota_window`，
   若某个环境把该索引漏建，并发下会短暂超发。迁移脚本已建，测试也覆盖了并发建行路径。
