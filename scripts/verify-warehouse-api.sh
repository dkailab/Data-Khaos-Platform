#!/usr/bin/env bash
# ==============================================================================
# PR A · 数仓数据 API 真实调用验收
#
# 从已运行的服务开始，走完整闭环：
#   分区就绪检查 → 创建草稿 → 草稿不可调用 → 发布 → 签发凭据
#   → 机器调用校验 → 固定契约调用 → 无效凭据 → 跨项目拒绝
#   → 吊销凭据 → 吊销后拒绝 → 下线 API → 下线后拒绝
#
# 结束（含异常退出）时自动吊销本次创建的凭据并下线本次创建的 API 版本。
#
# 用法：
#   DK_PASSWORD=<测试成员密码> bash scripts/verify-warehouse-api.sh
#
# 环境变量（全部有默认值，除密码/令牌外）：
#   DK_GATEWAY     网关地址           默认 http://localhost:8099
#   DK_PROJECT_ID  项目 ID            默认 pg_001
#   DK_USERNAME    测试成员账号       默认 lisi（需具备 task:develop）
#   DK_PASSWORD    测试成员密码       默认空；与 DK_TOKEN 二选一
#   DK_TOKEN       已有 JWT，跳过登录 默认空
#   DK_BIZ_DATE    业务日期           默认 2026-11-17
#   DK_ASSET_ID    受管物化资产 ID    默认 mt_pr2_ads_region_gmv
#   DK_API_CODE    临时 API 编码      默认 verify-gmv-<epoch>
#   DK_API_VERSION API 版本号         默认 1
#   DK_APP_NAME    凭据应用名         默认 verify-app
#   DK_TTL_HOURS   凭据有效期（小时） 默认 1
#   DK_CROSS_PROJECT 跨项目测试用 ID  默认 pg_002
#
# 前提：
#   1. 目标服务已启动，网关可达；login 走 /api/auth/login。
#   2. 登录验证码在自动化环境需关闭：data-khaos.captcha.enabled=false
#   3. 已执行 db/migrate-paas-pr3.sql 等种子，且业务日已有「质量通过的受管物化发布」。
#      写模式必须为 REPLACE_BY_BIZ_DATE，数据源必须为 MySQL。
#   4. 目标表不得配置行列策略（有策略时受管查询按设计返回 403）。
#
# 退出码：0 全部通过；1 存在失败断言；2 缺少依赖或输入。
# ==============================================================================
set -u
set -o pipefail

GATEWAY="${DK_GATEWAY:-http://localhost:8099}"
PROJECT_ID="${DK_PROJECT_ID:-pg_001}"
USERNAME="${DK_USERNAME:-lisi}"
PASSWORD="${DK_PASSWORD:-}"
TOKEN="${DK_TOKEN:-}"
BIZ_DATE="${DK_BIZ_DATE:-2026-11-17}"
ASSET_ID="${DK_ASSET_ID:-mt_pr2_ads_region_gmv}"
API_CODE="${DK_API_CODE:-verify-gmv-$(date +%s)}"
API_VERSION="${DK_API_VERSION:-1}"
APP_NAME="${DK_APP_NAME:-verify-app}"
TTL_HOURS="${DK_TTL_HOURS:-1}"
CROSS_PROJECT="${DK_CROSS_PROJECT:-pg_002}"

MANAGE="/api/query/warehouse/v1/projects/${PROJECT_ID}/apis"
VERSION_PATH="${MANAGE}/${API_CODE}/versions/${API_VERSION}"
DATA_PATH="/api/data/v1/projects/${PROJECT_ID}/apis/${API_CODE}/versions/${API_VERSION}"

JWT=""
HTTP_STATUS="000"
RESP_BODY=""
RESP_CODE="-"
CHECKS=0
FAILED=0
REPORT=""

CREDENTIAL_ID=""
CREDENTIAL_TOKEN=""
API_CREATED=0
PUBLISHED=0
CURRENT_REVISION=0

step() { printf '\n== %s\n' "$1"; }
note() { printf '   %s\n' "$1"; }

# 记录一条断言。ok=1 通过，否则失败。
verdict() {
  local ok="$1" name="$2" want="$3" http="$4" code="$5" detail="${6:-}"
  CHECKS=$((CHECKS + 1))
  local tag="PASS"
  if [[ "$ok" != "1" ]]; then
    tag="FAIL"
    FAILED=$((FAILED + 1))
  fi
  printf '   [%s] %-38s 期望=%-16s http=%-3s code=%-4s %s\n' \
    "$tag" "$name" "$want" "$http" "$code" "$detail"
  REPORT="${REPORT}${tag}|${name}|${want}|${http}|${code}|${detail}
"
}

jget() { printf '%s' "$RESP_BODY" | jq -r "$1" 2>/dev/null; }

# request <METHOD> <PATH> [BODY] [AUTH]
# 结果写入 HTTP_STATUS / RESP_BODY / RESP_CODE（业务码），两者分开记录。
request() {
  local method="$1" path="$2" body="${3:-}" auth="${4:-}"
  local out
  out=$(mktemp "${TMPDIR:-/tmp}/dk-pr-a.XXXXXX") || { HTTP_STATUS="000"; RESP_BODY=""; RESP_CODE="-"; return 1; }
  local args=(-sS --max-time 30 -o "$out" -w '%{http_code}' -X "$method")
  [[ -n "$auth" ]] && args+=(-H "Authorization: $auth")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data-raw "$body")
  HTTP_STATUS=$(curl "${args[@]}" "${GATEWAY}${path}" 2>/dev/null)
  [[ -z "$HTTP_STATUS" ]] && HTTP_STATUS="000"
  RESP_BODY=$(cat "$out" 2>/dev/null)
  rm -f "$out"
  RESP_CODE=$(printf '%s' "$RESP_BODY" \
    | jq -r 'if type == "object" then ((.code // "-") | tostring) else "-" end' 2>/dev/null)
  [[ -z "$RESP_CODE" ]] && RESP_CODE="-"
  return 0
}

cleanup() {
  local rc=$?
  trap - EXIT
  if [[ -n "${JWT:-}" ]]; then
    if [[ -n "$CREDENTIAL_ID" ]]; then
      request POST "${VERSION_PATH}/credentials/${CREDENTIAL_ID}/revoke" "" "$JWT" >/dev/null 2>&1 || true
    fi
    if [[ "$API_CREATED" == "1" && "$PUBLISHED" == "1" ]]; then
      request POST "${VERSION_PATH}/offline?revision=${CURRENT_REVISION}" "" "$JWT" >/dev/null 2>&1 || true
    fi
  fi
  exit $rc
}
trap cleanup EXIT INT TERM

command -v curl >/dev/null 2>&1 || { echo "缺少 curl" >&2; exit 2; }
command -v jq   >/dev/null 2>&1 || { echo "缺少 jq" >&2; exit 2; }

printf 'PR A · 数仓数据 API 真实调用验收\n'
note "网关=${GATEWAY} 项目=${PROJECT_ID} 业务日=${BIZ_DATE}"
note "资产=${ASSET_ID} 临时 API=${API_CODE} v${API_VERSION}"

# ---------------------------------------------------------------- 1. 登录
step "1/13 获取平台令牌"
if [[ -n "$TOKEN" ]]; then
  JWT="$TOKEN"
  verdict 1 "复用 DK_TOKEN" "无需登录" "-" "-" "跳过登录"
else
  if [[ -z "$PASSWORD" ]]; then
    echo "缺少 DK_PASSWORD；或用 DK_TOKEN 直接传入 JWT" >&2
    exit 2
  fi
  request POST /api/auth/login "$(jq -n --arg u "$USERNAME" --arg p "$PASSWORD" '{username:$u,password:$p}')"
  JWT=$(jget '.data.token')
  [[ "$JWT" == "null" ]] && JWT=""
  if [[ "$RESP_CODE" == "0" && -n "$JWT" ]]; then
    verdict 1 "登录 $USERNAME" "code=0 且有 token" "$HTTP_STATUS" "$RESP_CODE" ""
  else
    verdict 0 "登录 $USERNAME" "code=0 且有 token" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
    note "若提示验证码错误/为空，请在测试环境设置 data-khaos.captcha.enabled=false"
    echo "登录失败，后续检查无法继续" >&2
    exit 1
  fi
fi
AUTH="Bearer $JWT"

# ------------------------------------------------- 2. 分区就绪检查（PR3 能力）
step "2/13 受管物化分区就绪检查"
request GET "/api/query/warehouse/v1/materializations/${ASSET_ID}/status?bizDate=${BIZ_DATE}" "" "$AUTH"
STATUS_PUB=$(jget '.data.publicationId // empty')
STATUS_Q=$(jget '.data.qualitySnapshotId // empty')
if [[ "$RESP_CODE" == "0" && -n "$STATUS_PUB" && -n "$STATUS_Q" ]]; then
  verdict 1 "分区就绪（发布+质量快照）" "code=0" "$HTTP_STATUS" "$RESP_CODE" "pub=$STATUS_PUB"
else
  verdict 0 "分区就绪（发布+质量快照）" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  note "业务日 $BIZ_DATE 需先有质量通过、REPLACE_BY_BIZ_DATE 写入的受管物化发布"
  note "若返回 403，说明目标表配置了行列策略，受管物化查询按设计不开放"
fi

# ---------------------------------------------------------------- 3. 创建草稿
step "3/13 创建临时 API 草稿"
request POST "$VERSION_PATH" "$(jq -n --arg n "验收临时 API" --argjson m 100 '{name:$n,maxRows:$m}')" "$AUTH"
DRAFT_REV=$(jget '.data.revision // empty')
DRAFT_STATUS=$(jget '.data.status // empty')
if [[ "$RESP_CODE" == "0" && "$DRAFT_REV" == "1" && "$DRAFT_STATUS" == "DRAFT" ]]; then
  API_CREATED=1
  CURRENT_REVISION=1
  verdict 1 "创建草稿（revision=1/DRAFT）" "code=0 且 DRAFT" "$HTTP_STATUS" "$RESP_CODE" ""
else
  verdict 0 "创建草稿（revision=1/DRAFT）" "code=0 且 DRAFT" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  note "若返回 DUPLICATE_KEY，说明 $API_CODE 已存在，可指定 DK_API_CODE 换一个编码"
fi

# ---------------------------------------------------- 4. 草稿不可被调用（版本未发布）
if [[ "$API_CREATED" == "1" ]]; then
  step "4/13 草稿阶段不可调用"
  request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "$AUTH"
  if [[ "$RESP_CODE" == "404" ]]; then
    verdict 1 "草稿调用被拒（未发布）" "code=404" "$HTTP_STATUS" "$RESP_CODE" ""
  else
    verdict 0 "草稿调用被拒（未发布）" "code=404" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
else
  step "4/13 草稿阶段不可调用（跳过）"
  verdict 0 "草稿调用被拒（未发布）" "前置未就绪" "-" "-" "草稿未创建"
fi

# ---------------------------------------------------------------- 5. 发布
step "5/13 发布 API 版本"
if [[ "$API_CREATED" == "1" ]]; then
  request POST "${VERSION_PATH}/publish?revision=${CURRENT_REVISION}" "" "$AUTH"
  PUB_STATUS=$(jget '.data.status // empty')
  PUB_REV=$(jget '.data.revision // empty')
  if [[ "$RESP_CODE" == "0" && "$PUB_STATUS" == "PUBLISHED" ]]; then
    PUBLISHED=1
    CURRENT_REVISION="$PUB_REV"
    verdict 1 "发布（revision→$PUB_REV）" "code=0 且 PUBLISHED" "$HTTP_STATUS" "$RESP_CODE" ""
  else
    verdict 0 "发布（revision→2）" "code=0 且 PUBLISHED" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
else
  verdict 0 "发布" "前置未就绪" "-" "-" "草稿未创建"
fi

# ---------------------------------------------------------------- 6. 签发凭据
step "6/13 签发机器调用凭据"
if [[ "$PUBLISHED" == "1" ]]; then
  request POST "${VERSION_PATH}/credentials" \
    "$(jq -n --arg a "$APP_NAME" --argjson t "$TTL_HOURS" '{appName:$a,ttlHours:$t}')" "$AUTH"
  CREDENTIAL_TOKEN=$(jget '.data.token // empty')
  CREDENTIAL_ID=$(jget '.data.credential.id // empty')
  if [[ "$RESP_CODE" == "0" && -n "$CREDENTIAL_TOKEN" && -n "$CREDENTIAL_ID" ]] \
     && printf '%s' "$CREDENTIAL_TOKEN" | grep -q '^dkwh_'; then
    verdict 1 "签发凭据（dkwh_ 前缀）" "code=0 且有明文" "$HTTP_STATUS" "$RESP_CODE" "id=$CREDENTIAL_ID"
  else
    verdict 0 "签发凭据（dkwh_ 前缀）" "code=0 且有明文" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
    note "签发要求非内置管理员账号，且具备项目 task:develop 与资产权限"
  fi
else
  verdict 0 "签发凭据" "前置未就绪" "-" "-" "API 未发布"
fi

# -------------------------------------------- 7. 机器调用并按契约校验
step "7/13 机器调用并校验契约"
MACHINE_PUB=""
MACHINE_Q=""
if [[ -n "$CREDENTIAL_TOKEN" ]]; then
  request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "Bearer $CREDENTIAL_TOKEN"
  MACHINE_PUB=$(jget '.data.publicationId // empty')
  MACHINE_Q=$(jget '.data.qualitySnapshotId // empty')
  ROW_COUNT=$(printf '%s' "$RESP_BODY" | jq -r '(.data.rows // []) | length' 2>/dev/null)
  SORTED=$(printf '%s' "$RESP_BODY" | jq -r '(.data.rows // []) | [.[].region] as $r | ($r == ($r | sort))' 2>/dev/null)
  BAD_GMV=$(printf '%s' "$RESP_BODY" \
    | jq -r '[(.data.rows // [])[] | select((.gmv | test("^-?[0-9]{1,12}\\.[0-9]{2}$")) | not) | .gmv] | length' 2>/dev/null)
  TRUNCATED=$(jget '.data.truncated // empty')

  if [[ "$RESP_CODE" == "0" && -n "$MACHINE_PUB" && -n "$MACHINE_Q" ]]; then
    verdict 1 "机器调用返回发布与质量证据" "code=0 且证据非空" "$HTTP_STATUS" "$RESP_CODE" "pub=$MACHINE_PUB"
  else
    verdict 0 "机器调用返回发布与质量证据" "code=0 且证据非空" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi

  if [[ "${SORTED:-false}" == "true" ]]; then
    verdict 1 "地区按 region 升序" "有序" "$HTTP_STATUS" "$RESP_CODE" "rows=$ROW_COUNT"
  else
    verdict 0 "地区按 region 升序" "有序" "$HTTP_STATUS" "$RESP_CODE" "rows=${ROW_COUNT:-?}"
  fi

  if [[ "${BAD_GMV:-1}" == "0" ]]; then
    verdict 1 "gmv 均为两位小数字符串" "全部合规" "$HTTP_STATUS" "$RESP_CODE" "样本=$(printf '%s' "$RESP_BODY" | jq -r '.data.rows[0].gmv // "-"')"
  else
    verdict 0 "gmv 均为两位小数字符串" "全部合规" "$HTTP_STATUS" "$RESP_CODE" "不合规条数=${BAD_GMV:-?}"
  fi

  if [[ -n "$STATUS_PUB" && "$MACHINE_PUB" == "$STATUS_PUB" ]]; then
    verdict 1 "机器调用与就绪检查发布一致" "同一 publicationId" "$HTTP_STATUS" "$RESP_CODE" ""
  elif [[ -z "$STATUS_PUB" ]]; then
    verdict 1 "机器调用与就绪检查发布一致" "跳过" "-" "-" "就绪检查未取得发布 ID"
  else
    verdict 0 "机器调用与就绪检查发布一致" "同一 publicationId" "$HTTP_STATUS" "$RESP_CODE" "就绪=$STATUS_PUB 调用=$MACHINE_PUB"
  fi
  note "truncated=${TRUNCATED:-?}（最多返回 100 行，多查一行仅用于判断截断）"
else
  verdict 0 "机器调用并校验契约" "前置未就绪" "-" "-" "无可用凭据"
fi

# ------------------------------------------- 8. 固定契约样例（平台登录身份）
step "8/13 固定契约样例 /api/data/v1/order-region-gmv"
request GET "/api/data/v1/order-region-gmv?bizDate=${BIZ_DATE}" "" "$AUTH"
FIXED_PUB=$(jget '.data.publicationId // empty')
if [[ "$RESP_CODE" == "0" && -n "$FIXED_PUB" ]]; then
  verdict 1 "固定契约返回发布证据" "code=0" "$HTTP_STATUS" "$RESP_CODE" "pub=$FIXED_PUB"
else
  verdict 0 "固定契约返回发布证据" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# ---------------------------------------------------------------- 9. 无效凭据
step "9/13 伪造凭据应被拒绝"
request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "Bearer dkwh_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
if [[ "$RESP_CODE" == "401" ]]; then
  verdict 1 "伪造凭据被拒" "code=401" "$HTTP_STATUS" "$RESP_CODE" ""
else
  verdict 0 "伪造凭据被拒" "code=401" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# ---------------------------------------------------------------- 10. 跨项目隔离
step "10/13 跨项目应不可见"
request GET "/api/query/warehouse/v1/projects/${CROSS_PROJECT}/apis/${API_CODE}/versions/${API_VERSION}" "" "$AUTH"
if [[ "$RESP_CODE" == "404" || "$RESP_CODE" == "403" ]]; then
  verdict 1 "跨项目访问被拒" "code=404/403" "$HTTP_STATUS" "$RESP_CODE" "project=$CROSS_PROJECT"
else
  verdict 0 "跨项目访问被拒" "code=404/403" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# ---------------------------------------------------------------- 11. 吊销凭据
step "11/13 吊销凭据后应拒绝"
if [[ -n "$CREDENTIAL_ID" ]]; then
  request POST "${VERSION_PATH}/credentials/${CREDENTIAL_ID}/revoke" "" "$AUTH"
  if [[ "$RESP_CODE" == "0" ]]; then
    verdict 1 "吊销凭据" "code=0" "$HTTP_STATUS" "$RESP_CODE" ""
  else
    verdict 0 "吊销凭据" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
  if [[ -n "$CREDENTIAL_TOKEN" ]]; then
    request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "Bearer $CREDENTIAL_TOKEN"
    if [[ "$RESP_CODE" == "401" ]]; then
      verdict 1 "吊销后调用被拒" "code=401" "$HTTP_STATUS" "$RESP_CODE" ""
    else
      verdict 0 "吊销后调用被拒" "code=401" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
    fi
  fi
  CREDENTIAL_ID=""
else
  verdict 0 "吊销凭据" "前置未就绪" "-" "-" "无凭据"
fi

# ---------------------------------------------------------------- 12. 下线 API
step "12/13 下线 API 后应拒绝"
if [[ "$PUBLISHED" == "1" ]]; then
  request POST "${VERSION_PATH}/offline?revision=${CURRENT_REVISION}" "" "$AUTH"
  OFF_STATUS=$(jget '.data.status // empty')
  if [[ "$RESP_CODE" == "0" && "$OFF_STATUS" == "OFFLINE" ]]; then
    PUBLISHED=0
    CURRENT_REVISION=$(jget '.data.revision // empty')
    verdict 1 "下线版本" "code=0 且 OFFLINE" "$HTTP_STATUS" "$RESP_CODE" ""
  else
    verdict 0 "下线版本" "code=0 且 OFFLINE" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
  request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "$AUTH"
  if [[ "$RESP_CODE" == "404" ]]; then
    verdict 1 "下线后调用被拒" "code=404" "$HTTP_STATUS" "$RESP_CODE" ""
  else
    verdict 0 "下线后调用被拒" "code=404" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
else
  verdict 0 "下线 API" "前置未就绪" "-" "-" "API 未发布"
fi

# ---------------------------------------------------------------- 13. 收尾确认
step "13/13 收尾：凭据与版本已回收"
request GET "${VERSION_PATH}/credentials?current=1&size=20" "" "$AUTH"
ACTIVE=$(printf '%s' "$RESP_BODY" \
  | jq -r '[.data.records // [] | .[] | select(.revokedAt == null)] | length' 2>/dev/null)
if [[ "$RESP_CODE" == "0" && "${ACTIVE:-1}" == "0" ]]; then
  verdict 1 "凭据已回收（吊销记录可查）" "无未吊销凭据" "$HTTP_STATUS" "$RESP_CODE" "active=0"
else
  verdict 0 "凭据已回收（吊销记录可查）" "无未吊销凭据" "$HTTP_STATUS" "$RESP_CODE" "active=${ACTIVE:-?} $(jget '.msg // empty')"
fi

# ---------------------------------------------------------------- 汇总
printf '\n==================== 验收汇总 ====================\n'
printf '检查项 %d，通过 %d，失败 %d\n' "$CHECKS" "$((CHECKS - FAILED))" "$FAILED"
if (( FAILED > 0 )); then
  printf '\n失败明细：\n'
  printf '%s' "$REPORT" | awk -F'|' '$1=="FAIL" {printf "  - %s（期望 %s，实际 http=%s code=%s）%s\n", $2, $3, $4, $5, $6}'
fi
cat <<'UNCOVERED'

本次未覆盖（需造数，复验步骤见 docs/paas-implementation-plan.md 与交接说明）：
  - 已发布空分区：目标日零行发布应返回空数组并保留发布元数据
  - 质量失败保留上一成功分区：在 DWS 提交后注入金额差异，ADS 不应产出
  - 目标版本标记不一致：篡改 dk_materialization_version 后应报「物化版本尚未就绪」
  - 缺少资产权限：用无该资产 SELECT 权限的成员调用应 403
UNCOVERED

if (( FAILED > 0 )); then
  printf '\n结果：FAIL\n'
  exit 1
fi
printf '\n结果：PASS\n'
exit 0
