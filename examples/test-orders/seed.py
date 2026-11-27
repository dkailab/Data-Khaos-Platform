#!/usr/bin/env python3
"""Populate and verify the isolated order demo in the local development stack."""

import argparse
from datetime import date, timedelta
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import uuid4


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
API = "http://127.0.0.1:5183/api"
PROJECT = "dk_demo_orders"
SCHEMA = "dk_test_orders"
SOURCE = "订单测试库"
TABLES = (
    "demo_raw_orders", "demo_ods_orders", "demo_dwd_paid_orders",
    "demo_dws_daily_sales", "demo_ads_region_sales",
)


def mysql(sql):
    result = subprocess.run(
        ["docker", "compose", "-f", "docker/docker-compose.yml", "exec", "-T", "mysql",
         "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B --default-character-set=utf8mb4'],
        cwd=ROOT, input=sql, text=True, stdout=subprocess.PIPE, check=True,
    )
    return result.stdout.strip()


def api(path, *, token=None, method="GET", payload=None, **params):
    url = API + path + ("?" + urlencode(params) if params else "")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    body = json.dumps(payload).encode() if payload is not None else None
    with urlopen(Request(url, data=body, headers=headers, method=method), timeout=25) as response:
        res = json.load(response)
    if res["code"] != 0:
        raise RuntimeError(f"{method} {path}: {res['msg']} ({res['code']})")
    return res["data"]


def setup_database():
    required = (
        "migrate-workflow-revisions.sql",
        "migrate-flink-studio.sql",
        "migrate-flink-deployment.sql",
        "migrate-flink-checkpoint-alert.sql",
        "migrate-flink-alert-notification.sql",
        "migrate-flink-pressure-alert.sql",
        "migrate-flink-kafka-alert.sql",
        "migrate-flink-event-time-alert.sql",
    )
    for migration in required:
        mysql((ROOT / "db" / migration).read_text())
    already_migrated = mysql(
        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='data_khaos' "
        "AND table_name='workflow_run' AND column_name='heartbeat_at';"
    )
    if already_migrated == "0":
        mysql((ROOT / "db/migrate-workflow-recovery.sql").read_text())
    mysql((HERE / "seed.sql").read_text())


def datasource(token):
    entries = api("/ds/page", token=token, keyword=SOURCE, current=1, size=10)["records"]
    existing = next((row for row in entries if row["dsName"] == SOURCE), None)
    if existing:
        if (existing.get("databaseName"), existing.get("host"), existing.get("username")) != (
            SCHEMA, "mysql", "dk_demo_orders",
        ):
            raise RuntimeError("同名数据源连接到其他环境，停止写入")
        ds_id = existing["id"]
    else:
        password = secrets.token_hex(24)
        mysql(
            "CREATE USER IF NOT EXISTS 'dk_demo_orders'@'%' IDENTIFIED BY '" + password + "';"
            "ALTER USER 'dk_demo_orders'@'%' IDENTIFIED BY '" + password + "';"
            "GRANT SELECT, SHOW VIEW ON dk_test_orders.* TO 'dk_demo_orders'@'%';"
            "GRANT INSERT, DELETE ON dk_test_orders.demo_ods_orders TO 'dk_demo_orders'@'%';"
        )
        api("/ds", token=token, method="POST", payload={
            "dsName": SOURCE, "dsType": "MYSQL", "host": "mysql", "port": 3306,
            "databaseName": SCHEMA, "username": "dk_demo_orders", "password": password, "status": 1,
        })
        entries = api("/ds/page", token=token, keyword=SOURCE, current=1, size=10)["records"]
        ds_id = next(row["id"] for row in entries if row["dsName"] == SOURCE)
    if not ds_id.isalnum():
        raise RuntimeError("数据源 ID 格式异常")
    if api(f"/ds/{ds_id}/test", token=token, method="POST") is not True:
        raise RuntimeError("订单测试数据源连接失败")
    bindings = "\n".join(
        f"""INSERT INTO sys_table_permission
(id, datasource_id, database_name, table_name, permission_type, project_group_id, grant_type, status)
VALUES ('dk_demo_orders_select_{i}', '{ds_id}', '{SCHEMA}', '{table}', 'SELECT', '{PROJECT}', 'PROJECT_GROUP', 1)
ON DUPLICATE KEY UPDATE datasource_id = VALUES(datasource_id);"""
        for i, table in enumerate(TABLES)
    )
    mysql(
        f"USE data_khaos; START TRANSACTION; {bindings}"
        f"INSERT INTO sg_project_group_resource (id, project_group_id, resource_type, resource_id) "
        f"VALUES ('dk_demo_orders_source','{PROJECT}','DATASOURCE','{ds_id}') "
        "ON DUPLICATE KEY UPDATE resource_id=VALUES(resource_id); COMMIT;"
    )
    return ds_id


def workflow(token, ds_id):
    mysql(f"""USE data_khaos;
INSERT INTO pipeline_task
(id, task_name, task_type, engine, source_ds_id, source_table, target_ds_id, target_table,
source_query, write_mode, date_column, project_group_id, status, create_by)
SELECT 'dk_demo_orders_ingest', '订单导入 ODS', 'SYNC', 'DB_SYNC', '{ds_id}', 'demo_raw_orders',
'{ds_id}', 'demo_ods_orders', 'SELECT * FROM demo_raw_orders WHERE biz_date = ''${{bizDate}}''',
'REPLACE_BY_BIZ_DATE', 'biz_date', '{PROJECT}', 1, id FROM sys_user WHERE username = 'test'
ON DUPLICATE KEY UPDATE id = pipeline_task.id;""")
    result = api("/workflow/page", token=token, keyword="订单数仓日处理", current=1, size=10)
    existing = next((row for row in result["records"] if row["code"] == "dk_demo_orders_daily"), None)
    if existing:
        return existing["id"]

    def node(code, title, kind, config, index):
        return {"nodeCode": code, "nodeName": title, "nodeType": kind,
                "configJson": json.dumps(config), "posX": 100 + index * 220, "posY": 100, "timeout": 90}

    nodes = [
        node("ingest", "订单导入 ODS", "PIPELINE", {"pipelineTaskId": "dk_demo_orders_ingest"}, 0),
        node("detail", "检查已支付订单", "SQL", {"datasourceId": ds_id,
             "sql": "SELECT COUNT(*) AS paid_count FROM demo_dwd_paid_orders WHERE biz_date = '${bizDate}'"}, 1),
        node("ads", "检查区域汇总", "SQL", {"datasourceId": ds_id,
             "sql": "SELECT region, paid_count, gmv FROM demo_ads_region_sales WHERE biz_date = '${bizDate}'"}, 2),
    ]
    saved = api("/workflow", token=token, method="POST", payload={
        "def": {"name": "订单数仓日处理", "code": "dk_demo_orders_daily", "projectId": PROJECT,
                "status": 1, "description": "按业务日期同步订单，再核对明细与区域汇总。"},
        "nodes": nodes,
        "edges": [{"fromCode": first, "toCode": second} for first, second in (("ingest", "detail"), ("detail", "ads"))],
    })
    return saved["id"]


def load_orders(token, wf_id):
    for index in range(7):
        day = (date(2026, 11, 20) + timedelta(days=index)).isoformat()
        accepted = api(f"/workflow/{wf_id}/runs", token=token, method="POST", payload={
            "bizDate": day, "requestId": str(uuid4()), "params": {},
        })
        run_id = accepted.get("runId")
        if not run_id:
            raise RuntimeError(f"业务日 {day} 未受理: {accepted.get('rejectCode')}")
        for _ in range(45):
            run = api(f"/workflow/run/{run_id}", token=token)
            status = run["runStatus"]
            if status == "SUCCESS":
                print(f"离线工作流 {day} SUCCESS", flush=True)
                break
            if status not in {"PENDING", "RUNNING"}:
                raise RuntimeError(f"工作流 {run_id} {status}: {run.get('errorMessage')}")
            time.sleep(2)
        else:
            raise RuntimeError(f"工作流 {run_id} 未在预期时间内完成")


def catalog(token, ds_id):
    api(f"/meta/sync/{ds_id}/{SCHEMA}", token=token, method="POST")
    edges = "\n".join(
        f"""INSERT INTO meta_table_lineage (id, source_table_id, target_table_id, relation_type)
SELECT 'dk_demo_orders_edge_{i}', src.id, dst.id, 'ETL'
FROM meta_database db JOIN meta_table src ON src.database_id = db.id
JOIN meta_table dst ON dst.database_id = db.id
WHERE db.datasource_id = '{ds_id}' AND db.database_name = '{SCHEMA}'
AND src.table_name = '{source}' AND dst.table_name = '{target}'
ON DUPLICATE KEY UPDATE source_table_id=VALUES(source_table_id), target_table_id=VALUES(target_table_id);"""
        for i, (source, target) in enumerate(zip(TABLES, TABLES[1:]))
    )
    mysql("USE data_khaos; START TRANSACTION;" + edges + "COMMIT;")
    assets = api("/meta/assets", token=token, q="demo_", current=1, size=100)["records"]
    assets = {row["tableName"]: row for row in assets if row["datasourceId"] == ds_id}
    if not set(TABLES) <= assets.keys():
        raise RuntimeError("目录尚未返回全部测试资产")
    last = assets[TABLES[-1]]["id"]
    detail = api(f"/meta/assets/{last}", token=token)
    assert detail["relations"], "汇总资产缺少血缘"
    print("数据地图：5 个资产，4 段血缘", flush=True)


def verify_orders(token, ds_id):
    for table, expected in zip(TABLES, (420, 420, 347, 105, 35)):
        result = api("/query/execute", token=token, method="POST", payload={
            "datasourceId": ds_id, "databaseName": SCHEMA, "sql": f"SELECT COUNT(*) AS total FROM {table}",
        })
        count = int(result["rows"][0]["total"])
        assert count == expected, f"{table}: 预期 {expected}，实际 {count}"
        print(f"{table}: {count}", flush=True)
    api("/query/execute", token=token, method="POST", payload={
        "datasourceId": ds_id, "databaseName": SCHEMA, "sql": "SELECT * FROM demo_ads_region_sales ORDER BY biz_date DESC, gmv DESC",
    })


def flink_sql(token):
    assets = f"/workflow/flink/projects/{PROJECT}/assets"
    samples = (
        ("STREAMING", "dk_demo_orders_stream", "模拟订单实时开窗", "'rows-per-second' = '4'", """
            INSERT INTO order_result
            SELECT window_start, COUNT(*), SUM(amount)
            FROM TABLE(TUMBLE(TABLE order_input, DESCRIPTOR(ts), INTERVAL '10' SECOND))
            GROUP BY window_start, window_end;"""),
        ("BATCH", "dk_demo_orders_batch", "模拟订单批量汇总", "'number-of-rows' = '120'", """
            INSERT INTO order_result
            SELECT CAST(TIMESTAMP '2026-11-26 00:00:00' AS TIMESTAMP(3)), COUNT(*), SUM(amount)
            FROM order_input;"""),
    )
    for mode, code, name, options, statement in samples:
        matches = api(assets, token=token, kind="JOB", mode=mode, keyword=code, current=1, size=10)
        asset = next((row for row in matches["records"] if row["code"] == code), None)
        if asset:
            asset_id, version = asset["id"], asset["currentVersion"]
        else:
            processing_time = ", ts AS PROCTIME()" if mode == "STREAMING" else ""
            source = f"""CREATE TEMPORARY TABLE order_input (
    order_id BIGINT, amount DECIMAL(12,2){processing_time}
) WITH ('connector' = 'datagen', {options}, 'fields.amount.min' = '10', 'fields.amount.max' = '500');
CREATE TEMPORARY TABLE order_result (
    period_start TIMESTAMP(3), orders BIGINT, gmv DECIMAL(38,2)
) WITH ('connector' = 'blackhole');
{statement.strip()}
"""
            created = api(assets, token=token, method="POST", payload={
                "kind": "JOB", "code": code, "name": name,
                "note": "开源 Flink 社区版运行演示。使用内置模拟事件与测试输出。",
                "definition": {"mode": mode, "parallelism": 1, "sql": source},
            })
            asset_id, version = created["assetId"], created["currentVersion"]

        asset_path = f"{assets}/{asset_id}"
        runs = api(f"{asset_path}/runs", token=token, current=1, size=10)["page"]["records"]
        existing = next((row for row in runs if row["version"] == version and row["state"] in {
            "PREPARING", "SUBMITTING", "RECONCILING", "SUBMITTED", "FINISHED",
        }), None)
        if existing is None:
            api(f"{asset_path}/versions/{version}/validate", token=token, method="POST")
            existing = api(f"{asset_path}/versions/{version}/runs", token=token, method="POST",
                           payload={"requestId": str(uuid4())})
        for _ in range(25):
            result = api(f"{asset_path}/runs/{existing['id']}/refresh", token=token, method="POST")
            state = result["state"]
            if state == "FINISHED" or (mode == "STREAMING" and result.get("engineState") == "RUNNING"):
                print(f"{name}: {result.get('engineState') or state}", flush=True)
                break
            if state in {"FAILED", "CANCELED", "STOPPED"}:
                raise RuntimeError(f"{name}: {state} {result.get('message')}")
            time.sleep(2)
        else:
            raise RuntimeError(f"{name} 未在预期时间内启动或完成")


def dashboard(token, ds_id):
    prefix = "/visual"
    name = "订单数仓 · 测试看板"
    records = api(f"{prefix}/dashboard/page", token=token, keyword=name, current=1, size=10)["records"]
    entry = next((row for row in records if row["name"] == name), None)
    dashboard_id = entry["id"] if entry else api(
        f"{prefix}/dashboard", token=token, method="POST",
        payload={"name": name, "description": "演示环境 · 2026-11-20 至 2026-11-26 的模拟订单", "status": 1},
    )
    boards = api(f"{prefix}/board/{dashboard_id}", token=token)
    if not boards:
        api(f"{prefix}/board", token=token, method="POST", payload={
            "dashboardId": dashboard_id, "boardName": "订单概览", "boardType": "ANALYSIS",
            "status": 1, "sortOrder": 0,
        })
        boards = api(f"{prefix}/board/{dashboard_id}", token=token)
    charts = (
        ("已支付订单", "NUMBER", "SELECT SUM(paid_count) AS orders FROM demo_ads_region_sales",
         {"valueColumn": "orders", "unit": " 单", "decimals": 0}, (16, 16, 280, 110), 1),
        ("成交金额", "NUMBER", "SELECT SUM(gmv) AS gmv FROM demo_ads_region_sales",
         {"valueColumn": "gmv", "unit": " 元", "decimals": 2}, (312, 16, 280, 110), 1),
        ("成交趋势", "LINE", "SELECT biz_date, SUM(gmv) AS gmv FROM demo_ads_region_sales GROUP BY biz_date ORDER BY biz_date",
         {"xAxisColumn": "biz_date", "valueColumn": "gmv"}, (16, 150, 576, 385), 7),
        ("区域成交额", "BAR", "SELECT region, SUM(gmv) AS gmv FROM demo_ads_region_sales GROUP BY region ORDER BY gmv DESC",
         {"xAxisColumn": "region", "valueColumn": "gmv"}, (608, 150, 576, 385), 5),
    )
    items = api(f"{prefix}/dashboard/{dashboard_id}/items", token=token)
    existing = {row["title"] for row in items}
    for title, chart_type, sql, config, geometry, _ in charts:
        if title in existing:
            continue
        x, y, width, height = geometry
        api(f"{prefix}/item", token=token, method="POST", payload={
            "dashboardId": dashboard_id, "boardId": boards[0]["id"], "title": title, "chartType": chart_type,
            "datasourceId": ds_id, "querySql": sql, "config": json.dumps(config), "posX": x, "posY": y,
            "width": width, "height": height,
        })
    items = api(f"{prefix}/dashboard/{dashboard_id}/items", token=token)
    by_title = {row["title"]: row["id"] for row in items}
    for title, _, _, _, _, expected in charts:
        data = api(f"{prefix}/item/{by_title[title]}/execute", token=token, method="POST")
        assert len(data["rows"]) == expected, f"{title} 返回行数异常"
    print("测试看板：4 张图均返回真实查询结果", flush=True)
    print(f"http://127.0.0.1:5183/visual/dashboard/view/{dashboard_id}", flush=True)


def quality(token, ds_id):
    prefix = "/dquality"
    rules = (
        ("DK-DEMO-ODS-ORDER-ID", "订单测试：订单号唯一", "UNIQUE", "order_id", None),
        ("DK-DEMO-ODS-AMOUNT", "订单测试：金额范围", "VALUE_RANGE", "amount", {"min": 0, "max": 1500}),
    )
    existing = api(f"{prefix}/rule/page", token=token, keyword="订单测试", current=1, size=25)["records"]
    known = {row["ruleCode"] for row in existing if row.get("projectGroupId") == PROJECT}
    for code, name, kind, column, config in rules:
        if code in known:
            continue
        api(f"{prefix}/rule", token=token, method="POST", payload={
            "projectGroupId": PROJECT, "ruleCode": code, "ruleName": name, "ruleType": kind,
            "datasourceId": ds_id, "databaseName": SCHEMA, "tableName": "demo_ods_orders",
            "columnName": column, "ruleConfig": json.dumps(config) if config else None, "status": 1,
        })
    existing = api(f"{prefix}/rule/page", token=token, keyword="订单测试", current=1, size=25)["records"]
    by_code = {row["ruleCode"]: row["id"] for row in existing if row.get("projectGroupId") == PROJECT}
    name = "订单测试：原始层体检"
    tasks = api(f"{prefix}/task/page", token=token, keyword=name, current=1, size=10)["records"]
    if not any(row["taskName"] == name and row.get("projectGroupId") == PROJECT for row in tasks):
        api(f"{prefix}/task", token=token, method="POST", payload={
            "projectGroupId": PROJECT, "taskName": name,
            "ruleIds": json.dumps([by_code[rule[0]] for rule in rules]), "status": 1,
        })
        tasks = api(f"{prefix}/task/page", token=token, keyword=name, current=1, size=10)["records"]
    task = next(row for row in tasks if row["taskName"] == name and row.get("projectGroupId") == PROJECT)
    snapshot = api(f"{prefix}/task/{task['id']}/run", token=token, method="POST")
    if snapshot["conclusion"] != "PASS" or snapshot["rulePass"] != len(rules):
        raise RuntimeError(f"质量未通过: {snapshot['id']} {snapshot.get('conclusion')} {snapshot.get('errorCode')}")
    print(f"质量结果：{snapshot['rulePass']} 条规则通过", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-runs", action="store_true", help="Use previously completed offline runs.")
    parser.add_argument("--flink", action="store_true", help="Run SQL samples on the bound community Flink cluster.")
    parser.add_argument("--dashboard", action="store_true", help="Create and verify a dashboard backed by the test ADS.")
    parser.add_argument("--quality", action="store_true", help="Run and verify quality rules against the order data.")
    args = parser.parse_args()
    token = api("/auth/login", method="POST", payload={
        "username": "test", "password": os.environ.get("DK_TEST_PASSWORD", "test"),
    })["token"]
    setup_database()
    projects = api("/permission/project-group/me", token=token)
    assert any(row["projectGroupId"] == PROJECT and "task:develop" in row["capabilities"] for row in projects)
    ds_id = datasource(token)
    wf_id = workflow(token, ds_id)
    if not args.skip_runs:
        load_orders(token, wf_id)
    catalog(token, ds_id)
    verify_orders(token, ds_id)
    if args.quality:
        quality(token, ds_id)
    if args.dashboard:
        dashboard(token, ds_id)
    if args.flink:
        flink_sql(token)


if __name__ == "__main__":
    main()
