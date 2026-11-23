#!/bin/bash
# PR2C 验收脚本（临时）：质量门禁与发布
# 覆盖：差异阻断 / 质量服务不可用 / 规则查询报错 / 零有效规则 / 修复后重跑
WF=http://localhost:8093
HS='-H Content-Type:application/json -H X-User-Id:1 -H X-Username:system -H X-Roles:SUPER_ADMIN'
DAG=wf_pr2c_orders
PASS=0; FAIL=0

chk() {
  if [ "$2" = "$3" ]; then echo "PASS $1"; PASS=$((PASS+1)); else echo "FAIL $1 | expected=$2 actual=$3"; FAIL=$((FAIL+1)); fi
}
q() { docker exec dk-mysql mysql -uroot -proot123456 -N -B data_khaos -e "$1" 2>/dev/null; }
jqf() { printf '%s' "$1" | jq -r "$2" 2>/dev/null; }

wf_trigger() { # requestId bizDate -> runId（失败返回空）
  local resp
  resp=$(curl -s -m 30 -X POST "$WF/api/workflow/$DAG/trigger?triggerType=MANUAL&requestId=$1&bizDate=$2" $HS)
  jqf "$resp" '.data.runId // empty'
}
wf_wait() { # runId timeoutSec -> runStatus
  local id=$1 t=${2:-180} i=0 st=""
  while [ $i -lt "$t" ]; do
    st=$(curl -s -m 10 "$WF/api/workflow/run/$id" $HS | jq -r '.data.runStatus')
    case "$st" in SUCCESS|FAILED|STOP) echo "$st"; return;; esac
    sleep 3; i=$((i+3))
  done
  echo TIMEOUT
}
node_status() { curl -s -m 10 "$WF/api/workflow/run/$1/nodes" $HS | jq -r --arg c "$2" '[.data[]|select(.nodeCode==$c)][0].status'; }
node_ref()   { curl -s -m 10 "$WF/api/workflow/run/$1/nodes" $HS | jq -r --arg c "$2" '[.data[]|select(.nodeCode==$c)][0].refId // empty'; }
node_id()    { curl -s -m 10 "$WF/api/workflow/run/$1/nodes" $HS | jq -r --arg c "$2" '[.data[]|select(.nodeCode==$c)][0].id // empty'; }
snapshot_conclusion() { q "SELECT conclusion FROM dquality_snapshot WHERE id='$1';"; }
snapshot_errcode()    { q "SELECT IFNULL(error_code,'') FROM dquality_snapshot WHERE id='$1';"; }
ads17() { q "SELECT FORMAT(gmv,2) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-17' AND region='华东';"; }
ads17_cnt() { q "SELECT COUNT(*) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-17';"; }

BAD_DWS_SQL="SELECT order_date AS stat_date, region, COUNT(*) AS order_cnt, SUM(amount) + 1 AS amount_sum FROM pr2_dwd_orders WHERE order_date = '\${bizDate}' GROUP BY order_date, region"

restore_rules() {
  q "UPDATE dquality_rule SET table_name='pr2_dws_region_daily' WHERE id='dqr_pr2c_dws_recon';"
  q "UPDATE dquality_rule SET status=1 WHERE id LIKE 'dqr_pr2c_dws_%';"
}
# 与 migrate-paas-pr2b.sql 中的定义保持一致（用双写单引号转义，避免拼接嵌套引号）
restore_dws() {
  q "UPDATE pipeline_task SET source_query='SELECT order_date AS stat_date, region, COUNT(*) AS order_cnt, SUM(amount) AS amount_sum FROM pr2_dwd_orders WHERE order_date = ''\${bizDate}'' GROUP BY order_date, region' WHERE id='pipe_pr2b_dws';"
}

echo "== 前置：17日 ADS 已发布（PR2B 结果） =="
BEFORE_GMV=$(ads17); BEFORE_CNT=$(ads17_cnt)
chk "前置 ADS17 华东" "300.50" "$BEFORE_GMV"
chk "前置 ADS17 行数" "2" "$BEFORE_CNT"

echo "== C1 注入 DWS 金额差异 → 门禁 FAIL，ADS 不发布 =="
q "UPDATE pipeline_task SET source_query=\"$BAD_DWS_SQL\" WHERE id='pipe_pr2b_dws';"
R1=$(wf_trigger pr2c-c1-diff 2026-11-17)
S1=$(wf_wait "$R1" 240)
chk "C1 运行终态=FAILED" "FAILED" "$S1"
chk "C1 ODS 成功" "SUCCESS" "$(node_status "$R1" n_ods)"
chk "C1 DWD 成功" "SUCCESS" "$(node_status "$R1" n_dwd)"
chk "C1 DWS 成功（差异已写入）" "SUCCESS" "$(node_status "$R1" n_dws)"
chk "C1 质量节点 FAILED" "FAILED" "$(node_status "$R1" n_quality)"
chk "C1 ADS 被跳过" "SKIPPED" "$(node_status "$R1" n_ads)"
SNAP1=$(node_ref "$R1" n_quality)
chk "C1 质量节点关联快照" "1" "$([ -n "$SNAP1" ] && echo 1 || echo 0)"
chk "C1 快照结论=FAIL" "FAIL" "$(snapshot_conclusion "$SNAP1")"
chk "C1 ADS17 保持原值" "$BEFORE_GMV" "$(ads17)"
chk "C1 ADS17 行数不变" "$BEFORE_CNT" "$(ads17_cnt)"
restore_dws

echo "== C2 质量服务不可用 → ERROR，ADS 不发布 =="
docker stop dk-dquality >/dev/null 2>&1
R2=$(wf_trigger pr2c-c2-down 2026-11-17)
S2=$(wf_wait "$R2" 240)
chk "C2 运行终态=FAILED" "FAILED" "$S2"
chk "C2 质量节点 FAILED" "FAILED" "$(node_status "$R2" n_quality)"
chk "C2 ADS 被跳过" "SKIPPED" "$(node_status "$R2" n_ads)"
SNAP2=$(node_ref "$R2" n_quality)
chk "C2 快照结论=ERROR" "ERROR" "$(snapshot_conclusion "$SNAP2")"
chk "C2 ADS17 保持原值" "$BEFORE_GMV" "$(ads17)"
docker start dk-dquality >/dev/null 2>&1
sleep 25

echo "== C3 规则查询报错 → ERROR，ADS 不发布 =="
q "UPDATE dquality_rule SET table_name='pr2_not_exist' WHERE id='dqr_pr2c_dws_recon';"
R3=$(wf_trigger pr2c-c3-badsql 2026-11-17)
S3=$(wf_wait "$R3" 240)
chk "C3 运行终态=FAILED" "FAILED" "$S3"
chk "C3 质量节点 FAILED" "FAILED" "$(node_status "$R3" n_quality)"
chk "C3 ADS 被跳过" "SKIPPED" "$(node_status "$R3" n_ads)"
SNAP3=$(node_ref "$R3" n_quality)
chk "C3 快照结论=ERROR" "ERROR" "$(snapshot_conclusion "$SNAP3")"
chk "C3 ADS17 保持原值" "$BEFORE_GMV" "$(ads17)"
restore_rules

echo "== C4 零有效规则 → ERROR，ADS 不发布 =="
q "UPDATE dquality_rule SET status=0 WHERE id LIKE 'dqr_pr2c_dws_%';"
R4=$(wf_trigger pr2c-c4-norule 2026-11-17)
S4=$(wf_wait "$R4" 240)
chk "C4 运行终态=FAILED" "FAILED" "$S4"
chk "C4 质量节点 FAILED" "FAILED" "$(node_status "$R4" n_quality)"
chk "C4 ADS 被跳过" "SKIPPED" "$(node_status "$R4" n_ads)"
SNAP4=$(node_ref "$R4" n_quality)
chk "C4 快照结论=ERROR" "ERROR" "$(snapshot_conclusion "$SNAP4")"
chk "C4 错误码=ZERO_VALID_RULES" "ZERO_VALID_RULES" "$(snapshot_errcode "$SNAP4")"
chk "C4 ADS17 保持原值" "$BEFORE_GMV" "$(ads17)"
restore_rules

echo "== C5 修复后用新请求重跑 → PASS，ADS 正确 =="
R5=$(wf_trigger pr2c-c5-pass 2026-11-17)
S5=$(wf_wait "$R5" 240)
chk "C5 运行终态=SUCCESS" "SUCCESS" "$S5"
chk "C5 质量节点 SUCCESS" "SUCCESS" "$(node_status "$R5" n_quality)"
chk "C5 ADS SUCCESS" "SUCCESS" "$(node_status "$R5" n_ads)"
SNAP5=$(node_ref "$R5" n_quality)
chk "C5 快照结论=PASS" "PASS" "$(snapshot_conclusion "$SNAP5")"
chk "C5 快照业务日期" "2026-11-17" "$(q "SELECT biz_date FROM dquality_snapshot WHERE id='$SNAP5';")"
chk "C5 快照关联节点运行ID" "$(node_id "$R5" n_quality)" "$(q "SELECT workflow_node_run_id FROM dquality_snapshot WHERE id='$SNAP5';")"
chk "C5 ADS17 华东" "300.50" "$(ads17)"
chk "C5 ADS17 行数" "2" "$(ads17_cnt)"
chk "C5 生成新快照（与 C1 不同）" "1" "$([ "$SNAP5" != "$SNAP1" ] && echo 1 || echo 0)"

echo
echo "PR2C 验收：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
