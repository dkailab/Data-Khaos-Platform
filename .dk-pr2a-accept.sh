#!/bin/bash
# PR2A 验收脚本（临时）：触发、业务日期、补数、幂等与活跃互斥
WF=http://localhost:8093
SC=http://localhost:8089
H1='-H Content-Type:application/json -H X-User-Id:pg_user_001 -H X-Username:zhangsan'
H2='-H Content-Type:application/json -H X-User-Id:pg_user_002 -H X-Username:lisi'
HS='-H Content-Type:application/json -H X-User-Id:1 -H X-Username:system -H X-Roles:SUPER_ADMIN'
PASS=0; FAIL=0

chk() { # name expected actual
  if [ "$2" = "$3" ]; then echo "PASS $1"; PASS=$((PASS+1)); else echo "FAIL $1 | expected=$2 actual=$3"; FAIL=$((FAIL+1)); fi
}
jqf() { echo "$1" | jq -r "$2" 2>/dev/null; }

echo "== T1 受理与幂等 =="
R1=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr1_orders/runs" $H1 -d '{"requestId":"acc-001","bizDate":"2026-11-15"}')
chk "T1 code" 0 "$(jqf "$R1" .code)"
chk "T1 state" ACCEPTED "$(jqf "$R1" .data.state)"
chk "T1 bizDate" 2026-11-15 "$(jqf "$R1" .data.bizDate)"
RUN1=$(jqf "$R1" .data.runId); echo "runId=$RUN1"
R2=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr1_orders/runs" $H1 -d '{"requestId":"acc-001","bizDate":"2026-11-15"}')
chk "T2 state" DUPLICATE "$(jqf "$R2" .data.state)"
chk "T2 same runId" "$RUN1" "$(jqf "$R2" .data.runId)"
R3=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr1_orders/runs" $H1 -d '{"requestId":"acc-001","bizDate":"2026-11-15","params":{"x":1}}')
chk "T3 state" REJECTED "$(jqf "$R3" .data.state)"
chk "T3 code" REQUEST_CONFLICT "$(jqf "$R3" .data.rejectCode)"
R4=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr1_orders/runs" $H1 -d '{"requestId":"acc-002","bizDate":"2026/11/18"}')
chk "T4 state" REJECTED "$(jqf "$R4" .data.state)"
chk "T4 code" INVALID_DATE "$(jqf "$R4" .data.rejectCode)"

echo "== T5 补数超范围 =="
R5=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr1_orders/backfills" $H1 -d '{"requestId":"bf-range","fromDate":"2026-10-01","toDate":"2026-11-15"}')
chk "T5 拒绝" 1 "$(jqf "$R5" ".code!=0" | sed 's/true/1/;s/false/0/')"
echo "msg=$(jqf "$R5" .msg)"

echo "== T6 补数：次日失败后重发 =="
docker exec dk-mysql mysql -uroot -proot123456 data_khaos -e "INSERT INTO workflow_bizdate_lock (id,wf_id,biz_date,run_id,create_time) VALUES ('lock_bf_test','wf_pr1_orders','2026-11-11','fake_active_run',NOW());" >/dev/null 2>&1
R6=$(curl -s -m 60 -X POST "$WF/api/workflow/wf_pr1_orders/backfills" $H1 -d '{"requestId":"bf-001","fromDate":"2026-11-10","toDate":"2026-11-12"}')
echo "$R6" | jq -c '.data.items[] | {bizDate,state,runId,rejectCode,activeRunId}'
chk "T6 首日受理" ACCEPTED "$(echo "$R6" | jq -r '.data.items[0].state')"
chk "T6 次日活跃拒绝" RUN_ALREADY_ACTIVE "$(echo "$R6" | jq -r '.data.items[1].rejectCode')"
chk "T6 次日活跃ID" fake_active_run "$(echo "$R6" | jq -r '.data.items[1].activeRunId')"
chk "T6 三日受理" ACCEPTED "$(echo "$R6" | jq -r '.data.items[2].state')"
BF1=$(echo "$R6" | jq -r '.data.items[0].runId'); BF3=$(echo "$R6" | jq -r '.data.items[2].runId')
docker exec dk-mysql mysql -uroot -proot123456 data_khaos -e "DELETE FROM workflow_bizdate_lock WHERE id='lock_bf_test';" >/dev/null 2>&1
R6b=$(curl -s -m 60 -X POST "$WF/api/workflow/wf_pr1_orders/backfills" $H1 -d '{"requestId":"bf-001","fromDate":"2026-11-10","toDate":"2026-11-12"}')
chk "T6 重发首日不重复" "$BF1" "$(echo "$R6b" | jq -r '.data.items[0].runId')"
chk "T6 重发首日 DUPLICATE" DUPLICATE "$(echo "$R6b" | jq -r '.data.items[0].state')"
chk "T6 重发次日补上" ACCEPTED "$(echo "$R6b" | jq -r '.data.items[1].state')"
chk "T6 重发三日不重复" "$BF3" "$(echo "$R6b" | jq -r '.data.items[2].runId')"
CNT=$(docker exec dk-mysql mysql -uroot -proot123456 -N -B data_khaos -e "SELECT COUNT(*) FROM workflow_run WHERE request_id LIKE 'bf-001%';" 2>/dev/null)
chk "T6 运行总数=3" 3 "$CNT"

echo "== T7 跨项目权限 =="
P1=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr2a_proj/runs" $H1 -d '{"requestId":"proj-001","bizDate":"2026-11-13"}')
chk "T7 非成员拒绝" PROJECT_FORBIDDEN "$(jqf "$P1" .data.rejectCode)"
P2=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr2a_proj/runs" $H2 -d '{"requestId":"proj-002","bizDate":"2026-11-13"}')
chk "T7 成员受理" ACCEPTED "$(jqf "$P2" .data.state)"
P3=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr2a_proj/runs" $HS -d '{"requestId":"proj-003","bizDate":"2026-11-19"}')
chk "T7 系统放行" ACCEPTED "$(jqf "$P3" .data.state)"

echo "== T8 活跃互斥（长耗时节点） =="
L1=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr2a_lock/runs" $H1 -d '{"requestId":"lock-001","bizDate":"2026-11-15"}')
chk "T8 首次受理" ACCEPTED "$(jqf "$L1" .data.state)"
LRUN=$(jqf "$L1" .data.runId)
L2=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr2a_lock/runs" $H1 -d '{"requestId":"lock-002","bizDate":"2026-11-15"}')
chk "T8 不同键抢占拒绝" RUN_ALREADY_ACTIVE "$(jqf "$L2" .data.rejectCode)"
chk "T8 活跃ID" "$LRUN" "$(jqf "$L2" .data.activeRunId)"
L3=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr2a_lock/runs" $H1 -d '{"requestId":"lock-001","bizDate":"2026-11-15"}')
chk "T8 同键幂等" "$LRUN" "$(jqf "$L3" .data.runId)"
sleep 30
LST=$(docker exec dk-mysql mysql -uroot -proot123456 -N -B data_khaos -e "SELECT run_status FROM workflow_run WHERE id='$LRUN';" 2>/dev/null)
chk "T8 运行终态" SUCCESS "$LST"
LLOCK=$(docker exec dk-mysql mysql -uroot -proot123456 -N -B data_khaos -e "SELECT COUNT(*) FROM workflow_bizdate_lock WHERE run_id='$LRUN';" 2>/dev/null)
chk "T8 锁已释放" 0 "$LLOCK"
L4=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr2a_lock/runs" $H1 -d '{"requestId":"lock-003","bizDate":"2026-11-15"}')
chk "T8 释放后可再受理" ACCEPTED "$(jqf "$L4" .data.state)"

echo "== T9/T10 并发 =="
(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr1_orders/runs" $H1 -d '{"requestId":"conc-001","bizDate":"2026-11-16"}' > /tmp/c1.json) &
(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr1_orders/runs" $H1 -d '{"requestId":"conc-001","bizDate":"2026-11-16"}' > /tmp/c2.json) &
wait
C1=$(jq -r '.data.runId' /tmp/c1.json); C2=$(jq -r '.data.runId' /tmp/c2.json)
chk "T9 并发同请求同 runId" "$C1" "$C2"
chk "T9 均成功" 00 "$(jq -r '.code' /tmp/c1.json)$(jq -r '.code' /tmp/c2.json)"
(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr1_orders/runs" $H1 -d '{"requestId":"conc-002","bizDate":"2026-11-14"}' > /tmp/c3.json) &
(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr1_orders/runs" $H1 -d '{"requestId":"conc-003","bizDate":"2026-11-14"}' > /tmp/c4.json) &
wait
S3=$(jq -r '.data.state' /tmp/c3.json); S4=$(jq -r '.data.state' /tmp/c4.json)
chk "T10 一同一拒" "ACCEPTED REJECTED" "$(echo "$S3 $S4" | tr ' ' '\n' | sort | tr '\n' ' ' | sed 's/ $//')"
AID=$(jq -r 'select(.data.rejectCode=="RUN_ALREADY_ACTIVE") | .data.activeRunId' /tmp/c3.json /tmp/c4.json | head -1)
chk "T10 回填活跃ID" "$(jq -r 'select(.data.state=="ACCEPTED") | .data.runId' /tmp/c3.json /tmp/c4.json | head -1)" "$AID"

echo "== T11 旧触发兼容 =="
O1=$(curl -s -m 30 -X POST "$WF/api/workflow/wf_pr1_orders/trigger" $H1 -d '{}')
chk "T11 code" 0 "$(jqf "$O1" .code)"
chk "T11 msg" "触发成功" "$(jqf "$O1" .msg)"
chk "T11 data 为 runId" yes "$(jqf "$O1" '.data|type=="string"' | sed 's/true/yes/;s/false/no/')"

echo "== T12 运行分页与旧记录 =="
G1=$(curl -s -m 30 "$WF/api/workflow/run/page?wfId=wf_pr1_orders&current=1&size=50" $H1)
chk "T12 旧记录可见" 1 "$(echo "$G1" | jq '[.data[]|select(.id=="2123316595944931330")]|length')"
chk "T12 旧记录无业务日" 1 "$(echo "$G1" | jq '[.data[]|select(.id=="2123316595944931330" and .bizDate==null)]|length')"
G2=$(curl -s -m 30 "$WF/api/workflow/run/page?wfId=wf_pr1_orders&bizDate=2026-11-15&current=1&size=50" $H1)
chk "T12 按业务日过滤" 0 "$(echo "$G2" | jq '[.data[]|select(.bizDate!=null and .bizDate!="2026-11-15")]|length')"

echo "== T13 调度手动触发 =="
curl -s -m 180 -X POST "$SC/api/schedule/job/job_pr1_wf/run" -H 'X-User-Id: 1' -H 'X-Username: admin' >/tmp/sc1.json
echo "runNow resp: $(cat /tmp/sc1.json)"
LG=$(docker exec dk-mysql mysql -uroot -proot123456 -N -B data_khaos -e "SELECT id FROM schedule_job_log WHERE job_id='job_pr1_wf' ORDER BY create_time DESC LIMIT 1;" 2>/dev/null)
echo "logId=$LG"
docker exec dk-mysql mysql -uroot -proot123456 data_khaos -e "SELECT id,trigger_type,biz_date,request_id,schedule_log_id,run_status FROM workflow_run WHERE schedule_log_id='$LG';" 2>&1 | grep -v Warning

echo "== T14 计划触发时刻 =="
sleep 20
docker exec dk-mysql mysql -uroot -proot123456 data_khaos -e "SELECT id,status,start_time,scheduled_at FROM schedule_job_log WHERE job_id='job_pr2a_sched' ORDER BY create_time DESC LIMIT 2; SELECT id,run_status,trigger_type,biz_date,request_id,scheduled_at FROM workflow_run WHERE schedule_job_id='job_pr2a_sched';" 2>&1 | grep -v Warning

echo "---- SUMMARY pass=$PASS fail=$FAIL ----"
