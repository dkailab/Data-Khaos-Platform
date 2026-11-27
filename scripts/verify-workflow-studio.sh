#!/usr/bin/env bash
# ==============================================================================
# 数据开发工作台（调度编排）验收
#
# 覆盖：
#   登录 → 建 SQL 文件 → 列表不返回正文 → 详情/发布
#   → 建任务流（SQL 外部任务 / 延时器 / SQL 自定义 / 跳过节点 / 前置条件 / 节点参数）
#   → 保存两次验证节点 UUID 稳定 → 任务列表 → 单节点调试
#   → 整图运行（等待终态、校验节点状态）→ 运行当前及下游（只产生子集节点运行）
#   → 发布（版本快照绑定）→ 上游依赖门禁拒绝 → 解除依赖 → 下线
#   → 清理本次创建的任务流与 SQL 文件
#
# 用法：
#   DK_USERNAME=<项目组长账号> DK_PASSWORD=<密码> bash scripts/verify-workflow-studio.sh
#
# 环境变量：
#   DK_GATEWAY     网关地址        默认 http://localhost:8099
#   DK_USERNAME    登录账号        必填（需 task:develop + task:schedule）
#   DK_PASSWORD    登录密码        必填；与 DK_TOKEN 二选一
#   DK_TOKEN       已有 JWT        默认空（可替代账号密码）
#   DK_PROJECT_ID  项目ID          默认 pg_001
#   DK_DATASOURCE  测试数据源ID    默认 ds_app（需可执行 SELECT 1）
#   DK_BIZ_DATE    业务日期        默认 2026-11-27
#
# 退出码：0 全部通过；1 存在失败断言；2 缺少依赖或输入。
# ==============================================================================
set -u
set -o pipefail

GATEWAY="${DK_GATEWAY:-http://localhost:8099}"
USERNAME="${DK_USERNAME:-}"
PASSWORD="${DK_PASSWORD:-}"
TOKEN="${DK_TOKEN:-}"
PROJECT_ID="${DK_PROJECT_ID:-pg_001}"
DATASOURCE="${DK_DATASOURCE:-ds_app}"
BIZ_DATE="${DK_BIZ_DATE:-2026-11-27}"
STAMP="$(date +%s)"
SQL_NAME="verify-studio-${STAMP}.sql"
WF_A_NAME="验收-任务流A-${STAMP}"
WF_B_NAME="验收-任务流B-${STAMP}"
WF_C_NAME="验收-子任务流C-${STAMP}"
SQL_FOLDER="/SQL文件/验收"

WF_A_ID=""
WF_B_ID=""
WF_C_ID=""
SQL_FILE_ID=""

CHECKS=0
FAILED=0
REPORT=""

command -v jq >/dev/null 2>&1 || { echo "缺少 jq" >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "缺少 curl" >&2; exit 2; }

pass() {
  CHECKS=$((CHECKS + 1))
  REPORT="${REPORT}PASS  $1"$'\n'
  printf '  ✔ %s\n' "$1"
}

fail() {
  CHECKS=$((CHECKS + 1))
  FAILED=$((FAILED + 1))
  REPORT="${REPORT}FAIL  $1"$'\n'
  printf '  ✘ %s\n' "$1"
}

check_eq() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then pass "$label"; else fail "${label}（期望 ${expected}，实际 ${actual}）"; fi
}

check_true() {
  local label="$1" actual="$2"
  if [ "$actual" = "true" ]; then pass "$label"; else fail "${label}（实际 ${actual}）"; fi
}

request() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS -X "$method" "${GATEWAY}${path}" -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json')
  if [ -n "$body" ]; then args+=(-d "$body"); fi
  curl "${args[@]}"
}

require_code_zero() {
  local label="$1" response="$2"
  local code
  code="$(printf '%s' "$response" | jq -r '.code // "no-body"')"
  if [ "$code" = "0" ]; then pass "$label"; else fail "${label}（code=${code}，msg=$(printf '%s' "$response" | jq -r '.msg // ""')）"; fi
}

poll_run() {
  local run_id="$1" label="$2" i status
  for i in $(seq 1 45); do
    status="$(request GET "/api/workflow/run/${run_id}" | jq -r '.data.runStatus // empty')"
    case "$status" in
      SUCCESS) pass "$label 到达 SUCCESS"; return 0 ;;
      FAILED|STOP|RECOVERY_REQUIRED)
        fail "$label 终态 $status"; return 1 ;;
    esac
    sleep 1
  done
  fail "$label 超时未完成"
  return 1
}

cleanup() {
  if [ -n "$WF_A_ID" ]; then request DELETE "/api/workflow/${WF_A_ID}" >/dev/null 2>&1 || true; fi
  if [ -n "$WF_B_ID" ]; then request DELETE "/api/workflow/${WF_B_ID}" >/dev/null 2>&1 || true; fi
  if [ -n "$WF_C_ID" ]; then request DELETE "/api/workflow/${WF_C_ID}" >/dev/null 2>&1 || true; fi
  if [ -n "$SQL_FILE_ID" ]; then request DELETE "/api/workflow/sql-file/${SQL_FILE_ID}" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

echo "== 数据开发工作台验收 =="
echo "网关: $GATEWAY  项目: $PROJECT_ID  数据源: $DATASOURCE  业务日期: $BIZ_DATE"

if [ -z "$TOKEN" ]; then
  if [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
    echo "缺少 DK_USERNAME/DK_PASSWORD；或用 DK_TOKEN 直接传入 JWT" >&2
    exit 2
  fi
  LOGIN_BODY="$(jq -n --arg u "$USERNAME" --arg p "$PASSWORD" '{username:$u,password:$p}')"
  LOGIN_RESP="$(curl -sS -X POST "${GATEWAY}/api/auth/login" -H 'Content-Type: application/json' -d "$LOGIN_BODY")"
  TOKEN="$(printf '%s' "$LOGIN_RESP" | jq -r '.data.token // empty')"
  [ -n "$TOKEN" ] || { echo "登录失败: $(printf '%s' "$LOGIN_RESP" | head -c 300)" >&2; exit 2; }
fi
pass "登录成功"

# 1. SQL 文件
SQL_SAVE="$(request POST "/api/workflow/sql-file" "$(jq -n \
  --arg p "$PROJECT_ID" --arg f "$SQL_FOLDER" --arg n "$SQL_NAME" --arg ds "$DATASOURCE" \
  '{projectId:$p,folder:$f,name:$n,description:"验收用 SQL 文件",datasourceId:$ds,content:"SELECT 1 AS ok"}')")"
require_code_zero "创建 SQL 文件" "$SQL_SAVE"
SQL_FILE_ID="$(printf '%s' "$SQL_SAVE" | jq -r '.data.id // empty')"
[ -n "$SQL_FILE_ID" ] || { echo "SQL 文件 ID 为空，终止" >&2; exit 1; }

LIST="$(request GET "/api/workflow/sql-file/list?projectId=${PROJECT_ID}&keyword=${SQL_NAME}")"
check_eq "SQL 文件列表命中 1 条" "1" "$(printf '%s' "$LIST" | jq -r '.data | length')"
check_eq "列表不返回正文" "null" "$(printf '%s' "$LIST" | jq -r '.data[0].draftContent')"

DETAIL="$(request GET "/api/workflow/sql-file/${SQL_FILE_ID}")"
check_true "详情返回正文" "$(printf '%s' "$DETAIL" | jq -r '.data.draftContent == "SELECT 1 AS ok"')"
PUBLISH_SQL="$(request POST "/api/workflow/sql-file/${SQL_FILE_ID}/publish")"
require_code_zero "发布 SQL 文件" "$PUBLISH_SQL"
check_eq "SQL 文件发布版本" "1" "$(printf '%s' "$PUBLISH_SQL" | jq -r '.data.publishedVersion')"

# 2. 创建任务流 A：4 个节点 + 3 条依赖
wf_graph() {
  jq -n \
    --arg name "$1" --arg project "$PROJECT_ID" --arg sqlFile "$SQL_FILE_ID" --arg ds "$DATASOURCE" \
    --arg child "$WF_C_ID" \
    '{
      def: {name:$name, projectId:$project, status:1, description:"验收任务流", cronExpression:"", params:"{\"env\":\"verify\"}"},
      nodes: [
        {nodeCode:"n1", nodeName:"外部SQL", description:"引用已发布SQL文件", nodeType:"SQL", posX:80, posY:80,
         timeout:120, retryCount:0, retryInterval:0, skipFlag:0, executorGroup:"default group",
         independentSchedule:1, precondition:"",
         nodeParams:"{\"source\":\"external\"}",
         configJson:("{\"taskMode\":\"EXTERNAL\",\"sqlFileId\":\"" + $sqlFile + "\",\"externalObject\":\"LATEST\",\"sqlSplitMode\":\"NONE\"}")},
        {nodeCode:"n2", nodeName:"等待1秒", description:"前置条件通过后延时", nodeType:"DELAY", posX:380, posY:80,
         timeout:60, retryCount:0, retryInterval:0, skipFlag:0, executorGroup:"default group",
         independentSchedule:0, precondition:"${bizDate} >= '\''2026-01-01'\''",
         nodeParams:"", configJson:"{\"delaySeconds\":1}"},
        {nodeCode:"n3", nodeName:"自定义SQL", description:"单节点调试目标", nodeType:"SQL", posX:680, posY:80,
         timeout:120, retryCount:1, retryInterval:1, skipFlag:0, executorGroup:"default group",
         independentSchedule:1, precondition:"",
         nodeParams:"",
         configJson:("{\"taskMode\":\"CUSTOM\",\"sql\":\"SELECT 1 AS ok\",\"datasourceId\":\"" + $ds + "\",\"sqlSplitMode\":\"NONE\"}")},
        {nodeCode:"n4", nodeName:"跳过节点", description:"跳过仍释放下游", nodeType:"DELAY", posX:980, posY:80,
         timeout:60, retryCount:0, retryInterval:0, skipFlag:1, executorGroup:"default group",
         independentSchedule:0, precondition:"",
         nodeParams:"", configJson:"{\"delaySeconds\":30}"},
        {nodeCode:"n5", nodeName:"嵌套子任务流", description:"触发并等待子任务流", nodeType:"NESTED", posX:1280, posY:80,
         timeout:300, retryCount:0, retryInterval:0, skipFlag:0, executorGroup:"default group",
         independentSchedule:0, precondition:"",
         nodeParams:"", configJson:("{\"workflowId\":\"" + $child + "\"}")}
      ],
      edges: [
        {fromCode:"n1", toCode:"n2"},
        {fromCode:"n2", toCode:"n3"},
        {fromCode:"n3", toCode:"n4"},
        {fromCode:"n4", toCode:"n5"}
      ]
    }'
}

# 先建子任务流 C，供 A 的 NESTED 节点引用
C_BODY="$(jq -n --arg name "$WF_C_NAME" --arg project "$PROJECT_ID" --arg ds "$DATASOURCE" '{
  def:{name:$name,projectId:$project,status:1,description:"嵌套子任务流",cronExpression:"",params:""},
  nodes:[{nodeCode:"n1",nodeName:"子流SQL",nodeType:"SQL",posX:80,posY:80,timeout:120,retryCount:0,
          retryInterval:0,skipFlag:0,executorGroup:"default group",independentSchedule:0,precondition:"",
          nodeParams:"",configJson:("{\"taskMode\":\"CUSTOM\",\"sql\":\"SELECT 1\",\"datasourceId\":\"" + $ds + "\",\"sqlSplitMode\":\"NONE\"}")}],
  edges:[]
}')"
SAVE_C="$(request POST "/api/workflow" "$C_BODY")"
require_code_zero "创建子任务流 C" "$SAVE_C"
WF_C_ID="$(printf '%s' "$SAVE_C" | jq -r '.data.id // empty')"
[ -n "$WF_C_ID" ] || { echo "子任务流 C ID 为空，终止" >&2; exit 1; }
GRAPH_C="$(request GET "/api/workflow/${WF_C_ID}")"
check_eq "子任务流节点配置完整" "true" \
  "$(printf '%s' "$GRAPH_C" | jq -r '.data.nodes[0].configJson | contains("sqlSplitMode")')"

SAVE_A="$(request POST "/api/workflow" "$(wf_graph "$WF_A_NAME")")"
require_code_zero "创建任务流 A" "$SAVE_A"
WF_A_ID="$(printf '%s' "$SAVE_A" | jq -r '.data.id // empty')"
[ -n "$WF_A_ID" ] || { echo "任务流 A ID 为空，终止" >&2; exit 1; }

GRAPH_A="$(request GET "/api/workflow/${WF_A_ID}")"
check_eq "任务流 A 节点数" "5" "$(printf '%s' "$GRAPH_A" | jq -r '.data.nodes | length')"
check_eq "任务流 A 依赖数" "4" "$(printf '%s' "$GRAPH_A" | jq -r '.data.edges | length')"
N1_ID_BEFORE="$(printf '%s' "$GRAPH_A" | jq -r '.data.nodes[] | select(.nodeCode=="n1") | .id')"
check_eq "跳过标记落库" "1" "$(printf '%s' "$GRAPH_A" | jq -r '.data.nodes[] | select(.nodeCode=="n4") | .skipFlag')"
check_eq "前置条件落库" "true" "$(printf '%s' "$GRAPH_A" | jq -r '.data.nodes[] | select(.nodeCode=="n2") | (.precondition | contains("bizDate"))')"
check_eq "节点参数落库" "true" "$(printf '%s' "$GRAPH_A" | jq -r '.data.nodes[] | select(.nodeCode=="n1") | (.nodeParams | contains("external"))')"

# 再保存一次（携带已有节点ID），验证 UUID 稳定
RESAVE="$(request POST "/api/workflow" "$(printf '%s' "$GRAPH_A" | jq '{def:.data.def, nodes:.data.nodes, edges:.data.edges}')")"
require_code_zero "再次保存任务流 A" "$RESAVE"
GRAPH_A2="$(request GET "/api/workflow/${WF_A_ID}")"
N1_ID_AFTER="$(printf '%s' "$GRAPH_A2" | jq -r '.data.nodes[] | select(.nodeCode=="n1") | .id')"
check_eq "节点 UUID 跨保存稳定" "$N1_ID_BEFORE" "$N1_ID_AFTER"

# 3. 任务列表与调试
TASKS="$(request GET "/api/workflow/${WF_A_ID}/tasks")"
check_eq "任务列表节点数" "5" "$(printf '%s' "$TASKS" | jq -r '.data | length')"
check_eq "任务列表未运行状态" "null" "$(printf '%s' "$TASKS" | jq -r '.data[] | select(.nodeCode=="n3") | .lastStatus')"

DEBUG="$(request POST "/api/workflow/${WF_A_ID}/nodes/n3/debug" "$(jq -n --arg d "$BIZ_DATE" '{bizDate:$d}')")"
check_true "单节点调试成功" "$(printf '%s' "$DEBUG" | jq -r '.data.success')"
check_eq "调试影响/结果行数" "1" "$(printf '%s' "$DEBUG" | jq -r '.data.rows')"

# 4. 整图运行
RUN1="$(request POST "/api/workflow/${WF_A_ID}/runs" "$(jq -n --arg r "studio-full-${STAMP}" --arg d "$BIZ_DATE" '{requestId:$r,bizDate:$d,params:{}}')")"
check_eq "整图运行受理" "ACCEPTED" "$(printf '%s' "$RUN1" | jq -r '.data.state')"
RUN1_ID="$(printf '%s' "$RUN1" | jq -r '.data.runId // empty')"
[ -n "$RUN1_ID" ] || { echo "整图运行 ID 为空" >&2; exit 1; }
if poll_run "$RUN1_ID" "整图运行"; then
  NODES1="$(request GET "/api/workflow/run/${RUN1_ID}/nodes")"
  check_eq "整图节点运行数" "5" "$(printf '%s' "$NODES1" | jq -r '.data | length')"
  check_eq "跳过节点状态" "SKIPPED" "$(printf '%s' "$NODES1" | jq -r '.data[] | select(.nodeCode=="n4") | .status')"
  check_eq "外部SQL节点状态" "SUCCESS" "$(printf '%s' "$NODES1" | jq -r '.data[] | select(.nodeCode=="n1") | .status')"
  check_eq "嵌套任务流节点状态" "SUCCESS" "$(printf '%s' "$NODES1" | jq -r '.data[] | select(.nodeCode=="n5") | .status')"
fi

# 5. 运行当前及下游（n2 -> n2/n3/n4/n5）
RUN2="$(request POST "/api/workflow/${WF_A_ID}/runs" "$(jq -n --arg r "studio-part-${STAMP}" --arg d "$BIZ_DATE" '{requestId:$r,bizDate:$d,params:{},startCodes:["n2"]}')")"
check_eq "局部运行受理" "ACCEPTED" "$(printf '%s' "$RUN2" | jq -r '.data.state')"
RUN2_ID="$(printf '%s' "$RUN2" | jq -r '.data.runId // empty')"
if poll_run "$RUN2_ID" "局部运行"; then
  NODES2="$(request GET "/api/workflow/run/${RUN2_ID}/nodes")"
  check_eq "局部运行只含起始节点及下游" "4" "$(printf '%s' "$NODES2" | jq -r '.data | length')"
  check_eq "局部运行不含上游 n1" "0" "$(printf '%s' "$NODES2" | jq -r '[.data[] | select(.nodeCode=="n1")] | length')"
fi

# 6. 发布与下线
PUBLISH_WF="$(request POST "/api/workflow/${WF_A_ID}/publish")"
require_code_zero "发布任务流 A" "$PUBLISH_WF"
check_eq "发布状态" "2" "$(printf '%s' "$PUBLISH_WF" | jq -r '.data.status')"
REVISION="$(printf '%s' "$PUBLISH_WF" | jq -r '.data.revisionNumber // 0')"
[ "$REVISION" -ge 1 ] && pass "发布绑定版本号 v$REVISION" || fail "发布版本号异常: $REVISION"

# 7. 上游依赖门禁：B 未成功，A 的运行应被拒绝
SAVE_B="$(request POST "/api/workflow" "$(jq -n --arg name "$WF_B_NAME" --arg project "$PROJECT_ID" --arg ds "$DATASOURCE" '{
  def:{name:$name,projectId:$project,status:1,description:"上游任务流",cronExpression:"",params:""},
  nodes:[{nodeCode:"n1",nodeName:"上游SQL",nodeType:"SQL",posX:80,posY:80,timeout:120,retryCount:0,
          retryInterval:0,skipFlag:0,executorGroup:"default group",independentSchedule:0,precondition:"",
          nodeParams:"",configJson:("{\"taskMode\":\"CUSTOM\",\"sql\":\"SELECT 1\",\"datasourceId\":\"" + $ds + "\",\"sqlSplitMode\":\"NONE\"}")}],
  edges:[]
}')")"
require_code_zero "创建任务流 B" "$SAVE_B"
WF_B_ID="$(printf '%s' "$SAVE_B" | jq -r '.data.id // empty')"

DEPS="$(request PUT "/api/workflow/${WF_A_ID}/upstreams" "$(jq -n --arg b "$WF_B_ID" '[{upstreamWfId:$b,dependStrategy:"STRICT",enabled:true}]')")"
require_code_zero "配置上游依赖" "$DEPS"
check_eq "依赖列表数量" "1" "$(printf '%s' "$DEPS" | jq -r '.data | length')"

GRAPH_DEPS="$(request GET "/api/workflow/${WF_A_ID}/upstreams/graph?depth=2")"
check_eq "依赖图节点数" "2" "$(printf '%s' "$GRAPH_DEPS" | jq -r '.data.nodes | length')"
check_eq "依赖图方向为上游到当前" "$WF_B_ID" "$(printf '%s' "$GRAPH_DEPS" | jq -r '.data.edges[0].fromWfId')"

BLOCKED="$(request POST "/api/workflow/${WF_A_ID}/runs" "$(jq -n --arg r "studio-blocked-${STAMP}" --arg d "2026-11-26" '{requestId:$r,bizDate:$d,params:{}}')")"
check_eq "上游未成功时运行被拒绝" "REJECTED" "$(printf '%s' "$BLOCKED" | jq -r '.data.state')"
check_eq "拒绝码" "UPSTREAM_NOT_READY" "$(printf '%s' "$BLOCKED" | jq -r '.data.rejectCode')"

CLEAR_DEPS="$(request PUT "/api/workflow/${WF_A_ID}/upstreams" '[]')"
require_code_zero "解除上游依赖" "$CLEAR_DEPS"

OFFLINE="$(request POST "/api/workflow/${WF_A_ID}/offline")"
require_code_zero "下线任务流 A" "$OFFLINE"
check_eq "下线状态" "0" "$(printf '%s' "$OFFLINE" | jq -r '.data.status')"

echo
echo "== 结果：$((CHECKS - FAILED))/$CHECKS 通过 =="
if [ "$FAILED" -ne 0 ]; then
  echo "$REPORT" | grep '^FAIL' || true
  exit 1
fi
exit 0
