#!/bin/bash
# PR2B 验收脚本（临时）：按日重算订单数仓（REPLACE_BY_BIZ_DATE）
# 覆盖：两日逐层断言 / 重跑幂等 / 补数不串日 / 写入失败回滚 / 零行清理 / 缺日期报错
PL=http://localhost:8092
HS='-H Content-Type:application/json -H X-User-Id:1 -H X-Username:system -H X-Roles:SUPER_ADMIN'
PASS=0; FAIL=0

chk() { # name expected actual
  if [ "$2" = "$3" ]; then echo "PASS $1"; PASS=$((PASS+1)); else echo "FAIL $1 | expected=$2 actual=$3"; FAIL=$((FAIL+1)); fi
}
q() { docker exec dk-mysql mysql -uroot -proot123456 -N -B data_khaos -e "$1" 2>/dev/null; }
jqf() { printf '%s' "$1" | jq -r "$2" 2>/dev/null; }

trigger() { # taskId bizDate -> instanceId
  local resp
  resp=$(curl -s -m 30 -X POST "$PL/api/pipeline/task/$1/run?triggerType=MANUAL&bizDate=$2" $HS)
  jqf "$resp" '.data.id'
}
trigger_no_date() { # taskId -> instanceId
  local resp
  resp=$(curl -s -m 30 -X POST "$PL/api/pipeline/task/$1/run?triggerType=MANUAL" $HS)
  jqf "$resp" '.data.id'
}
wait_inst() { # instanceId timeoutSec -> status
  local id=$1 t=${2:-120} i=0 st="" resp
  while [ $i -lt "$t" ]; do
    resp=$(curl -s -m 10 "$PL/api/pipeline/instance/$id" $HS)
    st=$(jqf "$resp" '.data.status')
    if [ "$st" = "1" ] || [ "$st" = "2" ]; then echo "$st"; return; fi
    sleep 2; i=$((i+2))
  done
  echo TIMEOUT
}
inst_err() { local resp; resp=$(curl -s -m 10 "$PL/api/pipeline/instance/$1" $HS); jqf "$resp" '.data.errorMessage'; }
run_task() { # taskId bizDate -> status
  local id; id=$(trigger "$1" "$2"); wait_inst "$id" 180
}

echo "== T0 缺业务日期应失败 =="
ID0=$(trigger_no_date pipe_pr2b_ods)
S0=$(wait_inst "$ID0" 60)
chk "T0 状态=失败" 2 "$S0"
chk "T0 错误含未传入业务日期" 1 "$(inst_err "$ID0" | grep -c "未传入业务日期")"

echo "== T1 17日逐层 =="
chk "T1 ODS" 1 "$(run_task pipe_pr2b_ods 2026-11-17)"
chk "T1 DWD" 1 "$(run_task pipe_pr2b_dwd 2026-11-17)"
chk "T1 DWS" 1 "$(run_task pipe_pr2b_dws 2026-11-17)"
chk "T1 ADS" 1 "$(run_task pipe_pr2b_ads 2026-11-17)"
chk "T1 ODS行数" 4 "$(q "SELECT COUNT(*) FROM pr2_ods_orders WHERE order_date='2026-11-17';")"
chk "T1 DWD行数" 3 "$(q "SELECT COUNT(*) FROM pr2_dwd_orders WHERE order_date='2026-11-17';")"
chk "T1 DWS行数" 2 "$(q "SELECT COUNT(*) FROM pr2_dws_region_daily WHERE stat_date='2026-11-17';")"
chk "T1 DWS华东" "300.50" "$(q "SELECT FORMAT(amount_sum,2) FROM pr2_dws_region_daily WHERE stat_date='2026-11-17' AND region='华东';")"
chk "T1 DWS华北" "59.00" "$(q "SELECT FORMAT(amount_sum,2) FROM pr2_dws_region_daily WHERE stat_date='2026-11-17' AND region='华北';")"
chk "T1 ADS华东" "300.50" "$(q "SELECT FORMAT(gmv,2) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-17' AND region='华东';")"

echo "== T2 重跑17日结果不变 =="
chk "T2 ADS重跑" 1 "$(run_task pipe_pr2b_ads 2026-11-17)"
chk "T2 DWS华东不变" "300.50" "$(q "SELECT FORMAT(amount_sum,2) FROM pr2_dws_region_daily WHERE stat_date='2026-11-17' AND region='华东';")"
chk "T2 ODS无重复" 4 "$(q "SELECT COUNT(*) FROM pr2_ods_orders WHERE order_date='2026-11-17';")"

echo "== T3 补18日不串17日 =="
chk "T3 ODS" 1 "$(run_task pipe_pr2b_ods 2026-11-18)"
chk "T3 DWD" 1 "$(run_task pipe_pr2b_dwd 2026-11-18)"
chk "T3 DWS" 1 "$(run_task pipe_pr2b_dws 2026-11-18)"
chk "T3 ADS" 1 "$(run_task pipe_pr2b_ads 2026-11-18)"
chk "T3 ADS18华东" "150.00" "$(q "SELECT FORMAT(gmv,2) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-18' AND region='华东';")"
chk "T3 ADS18华南" "410.00" "$(q "SELECT FORMAT(gmv,2) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-18' AND region='华南';")"
chk "T3 ADS17华东不变" "300.50" "$(q "SELECT FORMAT(gmv,2) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-17' AND region='华东';")"
chk "T3 ADS17行数" 2 "$(q "SELECT COUNT(*) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-17';")"

echo "== T4 写入失败回滚保留原分区 =="
# 注意：不能在用例里直连 DELETE 目标数据——那是脚本自己的连接、独立提交，
# 引擎的事务回滚恢复不了。这里只注入写入失败，验证「删当日+插当日」整体回滚。
BEFORE=$(q "SELECT FORMAT(gmv,2) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-17' AND region='华东';")
q "CREATE TRIGGER trg_pr2_ads_fail BEFORE INSERT ON pr2_ads_region_gmv FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected write failure';"
ID4=$(trigger pipe_pr2b_ads 2026-11-17)
S4=$(wait_inst "$ID4" 120)
chk "T4 注入后状态=失败" 2 "$S4"
chk "T4 错误含注入信息" 1 "$(inst_err "$ID4" | grep -c "injected write failure")"
AFTER=$(q "SELECT FORMAT(gmv,2) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-17' AND region='华东';")
chk "T4 回滚后原值保留" "$BEFORE" "$AFTER"
q "DROP TRIGGER trg_pr2_ads_fail;"

echo "== T5 源侧删空后重算只清理当日分区 =="
q "DELETE FROM pr2_orders_src WHERE order_date='2026-11-18';"
chk "T5 ODS" 1 "$(run_task pipe_pr2b_ods 2026-11-18)"
chk "T5 DWD" 1 "$(run_task pipe_pr2b_dwd 2026-11-18)"
chk "T5 DWS" 1 "$(run_task pipe_pr2b_dws 2026-11-18)"
chk "T5 ADS" 1 "$(run_task pipe_pr2b_ads 2026-11-18)"
chk "T5 18日ODS清空" 0 "$(q "SELECT COUNT(*) FROM pr2_ods_orders WHERE order_date='2026-11-18';")"
chk "T5 18日DWS清空" 0 "$(q "SELECT COUNT(*) FROM pr2_dws_region_daily WHERE stat_date='2026-11-18';")"
chk "T5 18日ADS清空" 0 "$(q "SELECT COUNT(*) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-18';")"
chk "T5 17日ADS仍在" 2 "$(q "SELECT COUNT(*) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-17';")"
# 恢复 18 日源数据
q "INSERT INTO pr2_orders_src (order_id,region,amount,status,order_date) VALUES ('PR2-1801','华东',150.00,'PAID','2026-11-18'),('PR2-1802','华北',88.00,'PAID','2026-11-18'),('PR2-1803','华南',410.00,'PAID','2026-11-18');"
chk "T5 恢复ODS" 1 "$(run_task pipe_pr2b_ods 2026-11-18)"
chk "T5 恢复DWD" 1 "$(run_task pipe_pr2b_dwd 2026-11-18)"
chk "T5 恢复DWS" 1 "$(run_task pipe_pr2b_dws 2026-11-18)"
chk "T5 恢复ADS" 1 "$(run_task pipe_pr2b_ads 2026-11-18)"
chk "T5 恢复后ADS18华东" "150.00" "$(q "SELECT FORMAT(gmv,2) FROM pr2_ads_region_gmv WHERE stat_date='2026-11-18' AND region='华东';")"

echo "== T6 实例记录业务日期与写入策略 =="
chk "T6 实例bizDate" "2026-11-17" "$(q "SELECT biz_date FROM pipeline_instance WHERE task_id='pipe_pr2b_ads' AND biz_date='2026-11-17' ORDER BY create_time DESC LIMIT 1;")"
chk "T6 实例targetTable" "pr2_ads_region_gmv" "$(q "SELECT target_table FROM pipeline_instance WHERE task_id='pipe_pr2b_ads' ORDER BY create_time DESC LIMIT 1;")"
chk "T6 实例writeMode" "REPLACE_BY_BIZ_DATE" "$(q "SELECT write_mode FROM pipeline_instance WHERE task_id='pipe_pr2b_ads' ORDER BY create_time DESC LIMIT 1;")"

echo
echo "PR2B 验收：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
