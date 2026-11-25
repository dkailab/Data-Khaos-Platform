# PR D · 实时 ADS 契约与对账 — 交付说明

基线 `e99b5d9`。本 PR 在订单例子上补了**第二种数仓契约**：实时 ADS 的读 API 与对流批对账，
并把「时效」做成契约的一部分而不是调用方的猜测。

改动分三层：`examples/realtime-orders` 的数据面 → `query` 的契约与读取 → 对账接口。
存量 `BATCH` 契约行为完全不变（`data_mode` 默认 `BATCH`，老调用方无需改动）。

---

## 1. 改动入口

### 1.1 数据面（`examples/realtime-orders`）

作业原来只写 `ads_region_day(biz_date, region, paid_order_count, gmv_cents)`——**没有观测时间，
也没有任何「作业是否跑过」的信号**，导致两个无法回答的问题：结果有多新？空表是「当天没成交」
还是「作业还没跑」？本 PR 补齐这两类信号。

| 表 | 变更 | 作用 |
| --- | --- | --- |
| `ads_region_day` | 新增 `observed_at DATETIME(3) NOT NULL` | 结果观测时间，由作业用 `LOCALTIMESTAMP` 写入，是**可验证的更新时间来源** |
| `ads_region_day_progress` | 新增 | 业务日级推进：`advanced_at` / `event_count` / `max_event_time` |
| `ads_region_day_events` | 新增 | 作业级推进：区分「空数据」与「尚未产出」 |
| `ads_region_day_batch` | 补 `observed_at` | 批 ADS 同步有观测时间，对账两侧证据对等 |

表名约定：推进表由结果表名派生——结果级 `<table>_progress`、作业级 `<table>_events`。
这样契约不依赖固定表名，换一张 ADS 只要遵守命名即可复用。

`verify.py` 增加 `assert_freshness`，对每个场景断言 `observed_at` 落在合理窗口内、推进信号
确实前进（`event_count > 0`），否则实验环境里的「实时」无法自证。

`warehouse.sql` / `batch-reconcile.sql` 两个 Flink 作业同步改为显式列插入，避免 `SELECT *`
在加列后错位。

### 1.2 契约模型（`data-khaos-query`）

**迁移脚本**（必须先执行，顺序见脚本头）：

```
db/migrate-warehouse-api-realtime.sql
```

给 `warehouse_api_version` 增加 7 列，把实时契约的语义**固化在版本上**，调用方无需二次猜测：

| 列 | 默认 | 说明 |
| --- | --- | --- |
| `data_mode` | `BATCH` | `BATCH` 走既有分区发布证据；`REALTIME` 走观测时间与时效判定 |
| `realtime_asset_id` | NULL | 实时 ADS 资产，`REALTIME` 时必填 |
| `realtime_batch_asset_id` | NULL | 对账基线批资产，可选 |
| `observed_timezone` | `Asia/Shanghai` | `observed_at` 的解释时区，响应原样回显 |
| `amount_unit` | `CNY` | 实时表以**分**存储，契约固定按**元**两位小数返回 |
| `freshness_fresh_seconds` | `60` | `lag <= 60` → `FRESH` |
| `freshness_stale_seconds` | `300` | `lag <= 300` → `LAGGING`，超过 → `STALE` |

另给 `warehouse_api_call_audit` 补 `freshness`，事后可复核「当时返回的是哪种时效」。

**新增类**：

- `entity/WarehouseApiVersion`：加 `DataMode` 枚举与上述字段
- `dto/WarehouseApiDraft`：草稿写入模型，除 `name`/`maxRows` 外均可省略
- `dto/RealtimeRegionGmv`：实时契约响应
- `dto/RealtimeReconcile`：对账响应
- `service/RealtimeAdsQueryService`：读取 + 时效判定 + 对账
- `WarehouseApiService`：`invoke()` 返回值由 `OrderRegionGmv` 改为 `Object`，按 `dataMode` 分派；
  新增 `reconcile()`

### 1.3 验证种子（无 Flink 环境时用）

```
db/seed-realtime-ads.sql
```

在没有 Flink/Kafka 实验环境时复现同一套表形态与数据：建 `rt_ads_region_day` /
`rt_ads_region_day_events` / `rt_ads_region_day_batch`，观测时间取当下，并登记
`mt_rt_ads_region_day` / `mt_rt_ads_region_day_batch` 两个资产。可重复执行（先清后插）。

---

## 2. 接口样例

```bash
# 1) 建一个实时契约草稿（省略 name 之外的字段即为 BATCH，老行为）
curl -X POST "$GW/api/query/warehouse/v1/projects/pg_001/apis/region-gmv-rt/versions/1" \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"name":"地区成交额(实时)","maxRows":100,"dataMode":"REALTIME",
       "realtimeAssetId":"mt_rt_ads_region_day",
       "realtimeBatchAssetId":"mt_rt_ads_region_day_batch",
       "freshnessFreshSeconds":60,"freshnessStaleSeconds":300}'

# 2) 发布（revision 1 → 2）
curl -X POST ".../versions/1/publish?revision=1" -H "Authorization: Bearer $JWT"

# 3) 机器调用（凭据见 PR C）
curl -H "Authorization: Bearer dkwh_xxx" \
  "$GW/api/data/v1/projects/pg_001/apis/region-gmv-rt/versions/1?bizDate=2026-11-17"

# 4) 与批 ADS 逐地区对账
curl -H "Authorization: Bearer $JWT" \
  "$GW/api/query/warehouse/v1/projects/pg_001/apis/region-gmv-rt/versions/1/reconcile?bizDate=2026-11-17"
```

实时调用响应：

```json
{
  "bizDate": "2026-11-17",
  "mode": "REALTIME",
  "timezone": "Asia/Shanghai",
  "amountUnit": "CNY",
  "state": "PRODUCED",
  "observedAt": "2026-11-17T18:22:03.412+08:00",
  "lagSeconds": 12,
  "freshness": "FRESH",
  "thresholds": { "freshSeconds": 60, "staleSeconds": 300 },
  "progress": { "advancedAt": "...", "lagSeconds": 12, "eventCount": 3, "maxEventTime": "..." },
  "truncated": false,
  "regionCount": 2,
  "regions": [
    { "region": "east", "gmv": "100.00", "paidOrderCount": 2, "observedAt": "..." },
    { "region": "west", "gmv": "125.00", "paidOrderCount": 1, "observedAt": "..." }
  ]
}
```

对账响应包含两侧的时效和截断标记。任一侧尚未产出或超出 `maxRows` 时，`matched` 为 `false`，地区明细仍按上限返回。

```json
{
  "bizDate": "2026-11-17",
  "matched": true,
  "realtime": { "state": "PRODUCED", "observedAt": "...", "lagSeconds": 12, "freshness": "FRESH",
                "regionCount": 2, "amountUnit": "CNY", "truncated": false },
  "batch":    { "state": "PRODUCED", "observedAt": "...", "lagSeconds": 900, "freshness": "STALE",
                "regionCount": 2, "amountUnit": "CNY", "truncated": false },
  "regions": [
    { "region": "east", "realtimeGmv": "100.00", "batchGmv": "100.00",
      "realtimeOrderCount": 2, "batchOrderCount": 2, "verdict": "MATCHED" }
  ]
}
```

---

## 3. 语义约定（这几条决定调用方怎么写代码）

### 3.1 `state` 三态，不是「空/非空」两态

| state | 判定 | 调用方应理解为 |
| --- | --- | --- |
| `PRODUCED` | 结果表该业务日有行 | 有数据，看 `freshness` |
| `EMPTY` | 无行，但推进表有该业务日记录 | 当天**确实没有成交**，是有效结果 |
| `NOT_YET_PRODUCED` | 无行，且无任何推进信号 | 作业**还没跑到**，不能当 0 |

这是本 PR 最重要的语义。原来只有「查不到行」一种表现，把「还没跑」误判成「没成交」是典型
静默错误。

### 3.2 `freshness` 只看 `PRODUCED`

`EMPTY` / `NOT_YET_PRODUCED` 返回 `freshness: "UNKNOWN"`、`observedAt: null`——没有结果行就没有
「结果观测时间」，不应编造。

### 3.3 阈值边界是「不超过」

`lag <= freshSeconds` 为 `FRESH`（恰好等于阈值仍是新鲜）。`lag` 为负时钳制为 0（时钟回拨）。

### 3.4 金额与单位

实时表以**分**存储，契约固定按**元**返回两位小数字符串，正则 `-?[0-9]{1,14}\.[0-9]{2}` 校验，
与离线契约口径一致。`amountUnit` 随响应回显，不靠文档约定。

### 3.5 时区

`observed_at` 是墙钟时间，无时区信息。契约用版本上的 `observedTimezone` 解释它，响应里
`observedAt` 带 `+08:00` 偏移、`timezone` 原样回显。跨时区调用方不必自行换算。

---

## 4. 实际执行的验收

### 4.1 自动化（已执行，全部通过）

```bash
# 全项目离线编译
/Users/dk/apache-maven/bin/mvn -o -q compile -DskipTests          # exit 0

# 全项目测试
/Users/dk/apache-maven/bin/mvn -o test                            # BUILD SUCCESS
```

| 模块 | 用例数 | 说明 |
| --- | --- | --- |
| query | 37 | 含本 PR 新增 `RealtimeAdsQueryTest` 10 项 |
| workflow | 35 | |
| pipeline | 7 | |
| permission | 6 | |
| notification | 6 | |
| gateway | 2 | |

`RealtimeAdsQueryTest` 覆盖：

1. `gmv_cents → 元`两位小数换算，`amountUnit`/`timezone` 回显
2. 阈值边界：60 秒 → `FRESH`、61 秒 → `LAGGING`、300 秒 → `LAGGING`、301 秒 → `STALE`
3. 空表 + 有推进信号 → `EMPTY`；空表 + 无推进信号 → `NOT_YET_PRODUCED`（两者都不返回 `observedAt`）
4. 负 `lag`（时钟回拨）钳制为 0
5. 行数截断（`maxRows + 1` 探测，`truncated` 标记）
6. 对账：全一致 `matched=true`；`ONLY_REALTIME` / `ONLY_BATCH` / `MISMATCH` 三种差异
7. 实时侧 `FRESH` + 批侧 `STALE` 时两侧证据各自独立
8. 未配置批资产时对账明确报错，而不是返回空结果
9. 非法业务日 / 非法标识符拒绝

`WarehouseApiLifecycleTest` 补了端到端一路：建 `REALTIME` 草稿 → 发布 → 经控制器分派到实时服务，
确认版本能正确持久化 `dataMode` 与资产，且分派不落回 BATCH。

### 4.2 真实环境（**未执行**）

本机 Docker 已有 `dk-mysql`/`dk-redis`/`dk-nacos` 与全部服务容器，但运行中的镜像是 3 个月前
构建的，**不含 PR A/B/C/D 代码**。已完成的真实环境动作：

```bash
# 四个迁移脚本已应用到真实 MySQL（data_khaos）
migrate-warehouse-api-versions.sql → ...-credentials.sql → ...-apps.sql → ...-realtime.sql

# 验证种子已写入
db/seed-realtime-ads.sql        # rt_ads_region_day 2 行 + events 1 行 + batch 2 行 + 两个资产
```

**待执行**：重建 `data-khaos-query` 镜像并在真实 MySQL/Redis/网关上跑通：

1. 实时契约草稿 → 发布 → 应用凭据调用，断言 `state=PRODUCED`、`freshness=FRESH`、金额两位小数
2. 把种子观测时间改成 10 分钟前，重跑断言 `freshness=STALE`
3. 清空 `rt_ads_region_day` 但保留 `_events` 行 → 断言 `state=EMPTY`
4. 再清空 `_events` → 断言 `state=NOT_YET_PRODUCED`
5. 对账接口断言 `matched=true`；改一行实时金额 → 断言该地区 `MISMATCH`

### 4.3 未覆盖：CDC 增量与 TaskManager 恢复

plan 的 PR D 验收还要求「用 CDC 增量修改或取消订单核对实时 API 更新」「TaskManager 恢复后
与批模式逐地区对账」。这两项依赖 `examples/realtime-orders` 的 Docker 实验环境
（Flink + Kafka + MySQL CDC），本机未启动该环境，**未执行**。

`verify.py` 已按该环境补齐断言（`assert_freshness`），补齐实验环境后可直接跑。

---

## 5. 需要你知道的三项决策

### 5.1 推进信号做成两张表，而不是一个字段

只加一个 `advanced_at` 到结果表是无用的——结果表在「没成交」时根本不会有行，恰恰是最需要
信号的时候。所以推进信号必须**独立成表**，且分两级：

- 业务日级（`_progress`）：这个业务日有没有被处理过
- 作业级（`_events`）：作业消费了多少事件、最大事件时间

保留两级是因为「作业推进了但结果还没写出」（延迟）与「作业根本没跑」需要区分，前者应报
`LAGGING` 而不是 `NOT_YET_PRODUCED`。

### 5.2 时效阈值放版本，不放全局配置

不同 ADS 的产出节奏不同（分钟级 vs 小时级），全局阈值必然有一半是错的。放版本上，
发布时即固化，调用方从响应里读到 `thresholds` 就知道这套数字，不用查文档。

代价：改阈值要走新版本。考虑到契约稳定性要求，这是可接受的。

### 5.3 `invoke()` 返回 `Object` 而非泛型/联合类型

`BATCH` 返回 `OrderRegionGmv`、`REALTIME` 返回 `RealtimeRegionGmv`，两者字段语义差异大，
强行合并成一个 DTO 会造出一堆 `null` 字段。Java 没有联合类型，用 `Object` + 控制器
`R<Object>` 是最小改动方案。

代价：**编译期失去类型安全**，调用方必须看 `mode` 字段决定解析哪个结构。已在响应里固定
`mode: "REALTIME"` / `"BATCH"` 作为判别字段。

---

## 6. 待联调与遗留

| 项 | 状态 |
| --- | --- |
| 真实环境跑通实时契约与对账 | **未执行**（镜像需重建，见 4.2） |
| CDC 增量 / 取消订单的场景验证 | **未执行**（需 Flink+Kafka 实验环境） |
| TaskManager 恢复后与批 ADS 对账 | **未执行**（同上） |
| 前端实时契约编辑 | 工作台编辑名称与行数上限时保留原契约；实时字段可通过 API 显式更新 |
| 实时契约的权限控制 | 沿用表级校验；行列策略仍未接入（见路线图阶段八） |
| 网关限流与应用配额叠加 | 实时契约同样受 PR C 配额约束，且同样先撞网关 10rps IP 级粗限流 |
| 观测时间写入的时钟一致性 | 依赖作业与数据库同一时钟源；跨机部署需 NTP，否则 `lag` 会漂 |

---

## 7. 复验清单（重建镜像后按序执行）

```bash
# 1) 迁移（幂等：每条 ALTER 只执行一次，已执行则跳过）
for m in migrate-warehouse-api-versions migrate-warehouse-api-credentials \
         migrate-warehouse-api-apps migrate-warehouse-api-realtime; do
  docker exec -i dk-mysql sh -c 'export MYSQL_PWD=root123456; exec mysql -uroot' < "db/$m.sql"
done

# 2) 种子
docker exec -i dk-mysql sh -c 'export MYSQL_PWD=root123456; exec mysql -uroot' < db/seed-realtime-ads.sql

# 3) 重建 query 并重启（关闭验证码便于自动化）
cd docker && DOCKER_BUILDKIT=1 docker compose build query && docker compose up -d query gateway

# 4) 按 4.2 的 5 个场景断言
```
