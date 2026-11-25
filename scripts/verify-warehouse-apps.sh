#!/usr/bin/env bash
# ==============================================================================
# PR C · 数仓 API 应用注册、配额与调用审计验收
#
# 从已运行的服务开始，走完整闭环：
#   登录 → 分区就绪 → 发布临时 API → 注册两个应用 → 分别授权不同配额
#   → 签发应用凭据 → 超额返回 429 → 两应用额度互不影响 → 轮换凭据不重置额度
#   → 停用应用查询前拒绝 → 回收授权查询前拒绝 → 重新授权恢复
#   → 按 requestId 追审计 → 旧签发人委派凭据仍可用
#
# 结束（含异常退出）时自动吊销本次签发的凭据、回收授权并下线临时 API。
#
# 用法：
#   DK_PASSWORD=<测试成员密码> bash scripts/verify-warehouse-apps.sh
#
# 环境变量（除密码/令牌外均有默认值）：
#   DK_GATEWAY          网关地址            默认 http://localhost:8099
#   DK_PROJECT_ID       项目 ID             默认 pg_001
#   DK_USERNAME         测试成员账号        默认 lisi（需具备 task:develop）
#   DK_PASSWORD         测试成员密码        默认空；与 DK_TOKEN 二选一
#   DK_TOKEN            已有 JWT，跳过登录  默认空
#   DK_BIZ_DATE         业务日期            默认 2026-11-17
#   DK_ASSET_ID         受管物化资产 ID     默认 mt_pr2_ads_region_gmv
#   DK_API_CODE         临时 API 编码       默认 verify-app-<epoch>
#   DK_API_VERSION      API 版本号          默认 1
#   DK_RUNTIME_SUBJECT  应用运行主体        默认同 DK_USERNAME（需在该项目内且可用）
#   DK_QUOTA_A          应用 A 配额(次/分)  默认 2
#   DK_QUOTA_B          应用 B 配额(次/分)  默认 5
#   DK_TTL_HOURS        凭据有效期（小时）  默认 1
#
# 前提：
#   1. 目标服务已启动且网关可达；login 走 /api/auth/login。
#   2. 自动化环境需关闭登录验证码：data-khaos.captcha.enabled=false
#   3. 已执行 db/migrate-warehouse-api-apps.sql（应用/授权/配额窗口/审计四张表）。
#   4. 业务日已有「质量通过的受管物化发布」，目标表未配置行列策略。
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
STAMP="$(date +%s)"
API_CODE="${DK_API_CODE:-verify-app-${STAMP}}"
API_VERSION="${DK_API_VERSION:-1}"
RUNTIME_SUBJECT="${DK_RUNTIME_SUBJECT:-$USERNAME}"
QUOTA_A="${DK_QUOTA_A:-2}"
QUOTA_B="${DK_QUOTA_B:-5}"
TTL_HOURS="${DK_TTL_HOURS:-1}"

MANAGE="/api/query/warehouse/v1/projects/${PROJECT_ID}/apis"
VERSION_PATH="${MANAGE}/${API_CODE}/versions/${API_VERSION}"
DATA_PATH="/api/data/v1/projects/${PROJECT_ID}/apis/${API_CODE}/versions/${API_VERSION}"
APPS="/api/query/warehouse/v1/projects/${PROJECT_ID}/apps"
AUDITS="/api/query/warehouse/v1/projects/${PROJECT_ID}/audits"

JWT=""
HTTP_STATUS="000"
HTTP_RETRY_AFTER="-"
RESP_BODY=""
RESP_CODE="-"
CHECKS=0
FAILED=0
REPORT=""

API_CREATED=0
PUBLISHED=0
CURRENT_REVISION=0
APP_A=""
APP_B=""
APP_A_REVISION=0
TOKEN_A=""
TOKEN_A_ID=""
TOKEN_B=""
TOKEN_B_ID=""
TOKEN_A_ROTATED=""
TOKEN_A_ROTATED_ID=""
TOKEN_LEGACY=""
TOKEN_LEGACY_ID=""
GRANT_A=""
GRANT_B=""
APP_A_DISABLED=0

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
  printf '   [%s] %-40s 期望=%-16s http=%-3s code=%-4s %s\n' \
    "$tag" "$name" "$want" "$http" "$code" "$detail"
  REPORT="${REPORT}${tag}|${name}|${want}|${http}|${code}|${detail}
"
}

jget() { printf '%s' "$RESP_BODY" | jq -r "$1" 2>/dev/null; }

# 打印汇总并退出。<forced>=1 表示因前置条件缺失提前结束。
finish() {
  local forced="${1:-0}"
  printf '\n================ 汇总 ================\n'
  if [[ "$FAILED" == "0" && "$forced" != "1" ]]; then
    printf '全部通过：%d 项检查\n' "$CHECKS"
    printf '应用 A=%s 应用 B=%s API=%s v%s\n' "$APP_A" "$APP_B" "$API_CODE" "$API_VERSION"
    note "应用记录保留在项目中（当前无删除接口），凭据与授权已回收"
    trap - EXIT INT TERM
    exit 0
  fi
  printf '失败 %d / 共 %d 项检查\n\n' "$FAILED" "$CHECKS"
  printf '失败明细：\n'
  printf '%s' "$REPORT" | awk -F'|' '$1=="FAIL" {printf "  - %s | 期望=%s http=%s code=%s %s\n", $2, $3, $4, $5, $6}'
  printf '\n复现信息：网关=%s 项目=%s 业务日=%s API=%s v%s 应用A=%s 应用B=%s\n' \
    "$GATEWAY" "$PROJECT_ID" "$BIZ_DATE" "$API_CODE" "$API_VERSION" "$APP_A" "$APP_B"
  exit 1
}

# request <METHOD> <PATH> [BODY] [AUTH] [EXTRA_HEADER]
# 结果写入 HTTP_STATUS / RESP_BODY / RESP_CODE（业务码），两者分开记录。
request() {
  local method="$1" path="$2" body="${3:-}" auth="${4:-}" extra="${5:-}"
  local out hdr
  out=$(mktemp "${TMPDIR:-/tmp}/dk-pr-c.XXXXXX") || { HTTP_STATUS="000"; RESP_BODY=""; RESP_CODE="-"; return 1; }
  hdr=$(mktemp "${TMPDIR:-/tmp}/dk-pr-c-h.XXXXXX") || { HTTP_STATUS="000"; RESP_BODY=""; RESP_CODE="-"; return 1; }
  local args=(-sS --max-time 30 -o "$out" -D "$hdr" -w '%{http_code}' -X "$method")
  [[ -n "$auth" ]] && args+=(-H "Authorization: $auth")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data-raw "$body")
  [[ -n "$extra" ]] && args+=(-H "$extra")
  HTTP_STATUS=$(curl "${args[@]}" "${GATEWAY}${path}" 2>/dev/null)
  [[ -z "$HTTP_STATUS" ]] && HTTP_STATUS="000"
  RESP_BODY=$(cat "$out" 2>/dev/null)
  HTTP_RETRY_AFTER=$(awk 'BEGIN{IGNORECASE=1} /^retry-after:/ {gsub(/\r/,""); print $2}' "$hdr" 2>/dev/null | tail -1)
  [[ -z "$HTTP_RETRY_AFTER" ]] && HTTP_RETRY_AFTER="-"
  rm -f "$out" "$hdr"
  RESP_CODE=$(printf '%s' "$RESP_BODY" \
    | jq -r 'if type == "object" then ((.code // "-") | tostring) else "-" end' 2>/dev/null)
  [[ -z "$RESP_CODE" ]] && RESP_CODE="-"
  return 0
}

# revoke_token <appId|""> <tokenId>
revoke_token() {
  local app="$1" id="$2" path
  if [[ -z "$id" ]]; then return 0; fi
  if [[ -z "$app" ]]; then
    path="${VERSION_PATH}/credentials/${id}/revoke"
  else
    path="${APPS}/${app}/credentials/${API_CODE}/versions/${API_VERSION}/${id}/revoke"
  fi
  request POST "$path" "" "$AUTH" >/dev/null 2>&1 || true
}

cleanup() {
  local rc=$?
  trap - EXIT
  if [[ -n "${JWT:-}" ]]; then
    AUTH="Bearer $JWT"
    revoke_token "$APP_A" "$TOKEN_A_ID"
    revoke_token "$APP_A" "$TOKEN_A_ROTATED_ID"
    revoke_token "$APP_B" "$TOKEN_B_ID"
    revoke_token "" "$TOKEN_LEGACY_ID"
    if [[ -n "$GRANT_A" ]]; then
      request POST "${APPS}/${APP_A}/grants/${GRANT_A}/revoke" "" "$AUTH" >/dev/null 2>&1 || true
    fi
    if [[ -n "$GRANT_B" ]]; then
      request POST "${APPS}/${APP_B}/grants/${GRANT_B}/revoke" "" "$AUTH" >/dev/null 2>&1 || true
    fi
    if [[ "$APP_A_DISABLED" == "1" ]]; then
      request POST "${APPS}/${APP_A}/status?revision=${APP_A_REVISION}&enabled=true" "" "$AUTH" >/dev/null 2>&1 || true
    fi
    if [[ "$API_CREATED" == "1" && "$PUBLISHED" == "1" ]]; then
      request POST "${VERSION_PATH}/offline?revision=${CURRENT_REVISION}" "" "$AUTH" >/dev/null 2>&1 || true
    fi
  fi
  exit $rc
}
trap cleanup EXIT INT TERM

command -v curl >/dev/null 2>&1 || { echo "缺少 curl" >&2; exit 2; }
command -v jq   >/dev/null 2>&1 || { echo "缺少 jq" >&2; exit 2; }

printf 'PR C · 数仓 API 应用注册、配额与调用审计验收\n'
note "网关=${GATEWAY} 项目=${PROJECT_ID} 业务日=${BIZ_DATE}"
note "临时 API=${API_CODE} v${API_VERSION} 运行主体=${RUNTIME_SUBJECT}"
note "配额：应用A=${QUOTA_A}/分 应用B=${QUOTA_B}/分"

# ---------------------------------------------------------------- 1. 登录
step "1/14 获取平台令牌"
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
    finish 1
  fi
fi
AUTH="Bearer $JWT"

# ------------------------------------------------- 2. 分区就绪检查
step "2/14 受管物化分区就绪检查"
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

# ------------------------------------------------- 3. 发布临时 API
step "3/14 发布临时 API（授权前置条件）"
request POST "$VERSION_PATH" "$(jq -n --arg n "PRC 验收临时 API" --argjson m 100 '{name:$n,maxRows:$m}')" "$AUTH"
if [[ "$RESP_CODE" == "0" ]]; then
  API_CREATED=1
  CURRENT_REVISION=1
  request POST "${VERSION_PATH}/publish?revision=1" "" "$AUTH"
  if [[ "$RESP_CODE" == "0" ]]; then
    PUBLISHED=1
    CURRENT_REVISION=2
    verdict 1 "创建并发布临时 API" "code=0 且 PUBLISHED" "$HTTP_STATUS" "$RESP_CODE" ""
  else
    verdict 0 "创建并发布临时 API" "code=0 且 PUBLISHED" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
else
  verdict 0 "创建并发布临时 API" "code=0 且 PUBLISHED" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  note "若返回 DUPLICATE_KEY，说明 $API_CODE 已存在，可指定 DK_API_CODE 换一个编码"
fi

if [[ "$PUBLISHED" != "1" ]]; then
  note "缺少已发布版本，后续应用授权无法进行，提前结束"
  finish 1
fi

# ------------------------------------------------- 4. 注册两个应用
step "4/14 注册两个独立应用"
request POST "$APPS" "$(jq -n --arg n "verify-a-${STAMP}" --arg s "$RUNTIME_SUBJECT" '{name:$n,runtimeSubject:$s}')" "$AUTH"
APP_A=$(jget '.data.id // empty')
APP_A_REVISION=$(jget '.data.revision // 0')
APP_A_STATUS=$(jget '.data.status // empty')
if [[ "$RESP_CODE" == "0" && -n "$APP_A" && "$APP_A_STATUS" == "ENABLED" ]]; then
  verdict 1 "注册应用 A（appId 稳定身份）" "code=0 且 ENABLED" "$HTTP_STATUS" "$RESP_CODE" "appId=$APP_A"
else
  verdict 0 "注册应用 A（appId 稳定身份）" "code=0 且 ENABLED" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

request POST "$APPS" "$(jq -n --arg n "verify-b-${STAMP}" --arg s "$RUNTIME_SUBJECT" '{name:$n,runtimeSubject:$s}')" "$AUTH"
APP_B=$(jget '.data.id // empty')
if [[ "$RESP_CODE" == "0" && -n "$APP_B" ]]; then
  verdict 1 "注册应用 B" "code=0" "$HTTP_STATUS" "$RESP_CODE" "appId=$APP_B"
else
  verdict 0 "注册应用 B" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

if [[ -z "$APP_A" || -z "$APP_B" ]]; then
  note "应用未注册成功，后续检查无法继续"
  finish 1
fi

# ------------------------------------------------- 5. 分别授权不同配额
step "5/14 按 API 版本授权（配额 a=${QUOTA_A} b=${QUOTA_B}）"
request POST "${APPS}/${APP_A}/grants" \
  "$(jq -n --arg c "$API_CODE" --argjson v "$API_VERSION" --argjson q "$QUOTA_A" \
     '{apiCode:$c,version:$v,quotaPerMinute:$q}')" "$AUTH"
GRANT_A=$(jget '.data.id // empty')
if [[ "$RESP_CODE" == "0" && -n "$GRANT_A" ]]; then
  verdict 1 "授权应用 A" "code=0" "$HTTP_STATUS" "$RESP_CODE" "配额=${QUOTA_A}/分"
else
  verdict 0 "授权应用 A" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

request POST "${APPS}/${APP_B}/grants" \
  "$(jq -n --arg c "$API_CODE" --argjson v "$API_VERSION" --argjson q "$QUOTA_B" \
     '{apiCode:$c,version:$v,quotaPerMinute:$q}')" "$AUTH"
GRANT_B=$(jget '.data.id // empty')
if [[ "$RESP_CODE" == "0" && -n "$GRANT_B" ]]; then
  verdict 1 "授权应用 B" "code=0" "$HTTP_STATUS" "$RESP_CODE" "配额=${QUOTA_B}/分"
else
  verdict 0 "授权应用 B" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# 未授权版本不得签发
step "5b 未授权时拒发凭据"
request POST "${APPS}/${APP_A}/credentials/not-granted/versions/9" \
  "$(jq -n --argjson t "$TTL_HOURS" '{ttlHours:$t}')" "$AUTH"
if [[ "$RESP_CODE" == "404" || "$RESP_CODE" == "403" ]]; then
  verdict 1 "未授权版本拒发凭据" "code=403/404" "$HTTP_STATUS" "$RESP_CODE" ""
else
  verdict 0 "未授权版本拒发凭据" "code=403/404" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# ------------------------------------------------- 6. 签发应用凭据
step "6/14 签发应用凭据（明文仅返回一次）"
request POST "${APPS}/${APP_A}/credentials/${API_CODE}/versions/${API_VERSION}" \
  "$(jq -n --argjson t "$TTL_HOURS" '{ttlHours:$t}')" "$AUTH"
TOKEN_A=$(jget '.data.token // empty')
TOKEN_A_ID=$(jget '.data.credential.id // empty')
APP_ID_IN_TOKEN=$(jget '.data.credential.appId // empty')
if [[ "$RESP_CODE" == "0" && -n "$TOKEN_A" && "$APP_ID_IN_TOKEN" == "$APP_A" ]]; then
  verdict 1 "签发应用 A 凭据（归属 appId）" "code=0 且 appId 匹配" "$HTTP_STATUS" "$RESP_CODE" ""
else
  verdict 0 "签发应用 A 凭据（归属 appId）" "code=0 且 appId 匹配" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

request POST "${APPS}/${APP_B}/credentials/${API_CODE}/versions/${API_VERSION}" \
  "$(jq -n --argjson t "$TTL_HOURS" '{ttlHours:$t}')" "$AUTH"
TOKEN_B=$(jget '.data.token // empty')
TOKEN_B_ID=$(jget '.data.credential.id // empty')

request GET "${APPS}/${APP_A}/credentials/${API_CODE}/versions/${API_VERSION}" "" "$AUTH"
LEAKED=$(jget '[.data.records[] | select(has("token") or has("tokenDigest"))] | length')
if [[ "$RESP_CODE" == "0" && "$LEAKED" == "0" ]]; then
  verdict 1 "凭据列表不泄露明文与摘要" "无 token/tokenDigest" "$HTTP_STATUS" "$RESP_CODE" ""
else
  verdict 0 "凭据列表不泄露明文与摘要" "无 token/tokenDigest" "$HTTP_STATUS" "$RESP_CODE" "leak=$LEAKED"
fi

if [[ -z "$TOKEN_A" || -z "$TOKEN_B" ]]; then
  note "凭据未签发成功，后续调用检查无法继续"
  finish 1
fi
MACHINE_A="Bearer $TOKEN_A"
MACHINE_B="Bearer $TOKEN_B"

# ------------------------------------------------- 7. 超额返回 429
step "7/14 应用 A 超额返回 HTTP 429"
req_seq=0
for i in $(seq 1 "$QUOTA_A"); do
  req_seq=$((req_seq + 1))
  request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "$MACHINE_A" "X-Request-Id: prc-${STAMP}-ok-${i}"
  if [[ "$RESP_CODE" == "0" ]]; then
    verdict 1 "应用 A 第 ${i} 次调用放行" "code=0" "$HTTP_STATUS" "$RESP_CODE" ""
  else
    verdict 0 "应用 A 第 ${i} 次调用放行" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
done

request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "$MACHINE_A" "X-Request-Id: prc-${STAMP}-over"
if [[ "$HTTP_STATUS" == "429" && "$RESP_CODE" == "429" ]]; then
  verdict 1 "超额返回 429（HTTP 与业务码一致）" "http=429 code=429" "$HTTP_STATUS" "$RESP_CODE" \
    "retry-after=${HTTP_RETRY_AFTER:-未取到}"
else
  verdict 0 "超额返回 429（HTTP 与业务码一致）" "http=429 code=429" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# ------------------------------------------------- 8. 两应用额度互不影响
step "8/14 应用 B 额度独立，不受 A 超额影响"
request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "$MACHINE_B" "X-Request-Id: prc-${STAMP}-b1"
if [[ "$RESP_CODE" == "0" ]]; then
  verdict 1 "应用 B 在 A 超额后仍可调用" "code=0" "$HTTP_STATUS" "$RESP_CODE" ""
else
  verdict 0 "应用 B 在 A 超额后仍可调用" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# --------------------------------------------- 9. 轮换凭据不重置额度
step "9/14 凭据轮换保留应用额度"
request POST "${APPS}/${APP_A}/credentials/${API_CODE}/versions/${API_VERSION}" \
  "$(jq -n --argjson t "$TTL_HOURS" '{ttlHours:$t}')" "$AUTH"
TOKEN_A_ROTATED=$(jget '.data.token // empty')
TOKEN_A_ROTATED_ID=$(jget '.data.credential.id // empty')
if [[ -n "$TOKEN_A_ROTATED" ]]; then
  request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "Bearer $TOKEN_A_ROTATED" "X-Request-Id: prc-${STAMP}-rot"
  if [[ "$HTTP_STATUS" == "429" ]]; then
    verdict 1 "轮换后同一额度已用尽（仍 429）" "http=429" "$HTTP_STATUS" "$RESP_CODE" "额度未被重置"
  else
    verdict 0 "轮换后同一额度已用尽（仍 429）" "http=429" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
  # 旧凭据在轮换后仍可用（滚动切换期并存）
  request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "$MACHINE_A" "X-Request-Id: prc-${STAMP}-old"
  if [[ "$HTTP_STATUS" == "429" ]]; then
    verdict 1 "旧凭据仍有效且共用额度" "http=429" "$HTTP_STATUS" "$RESP_CODE" "多枚并存"
  else
    verdict 0 "旧凭据仍有效且共用额度" "http=429" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
else
  verdict 0 "凭据轮换" "可签发第二枚" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# ------------------------------------------------- 10. 停用应用
step "10/14 停用应用后查询前拒绝"
request POST "${APPS}/${APP_A}/status?revision=${APP_A_REVISION}&enabled=false" "" "$AUTH"
if [[ "$RESP_CODE" == "0" ]]; then
  APP_A_DISABLED=1
  APP_A_REVISION=$(jget '.data.revision // 0')
  request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "$MACHINE_A" "X-Request-Id: prc-${STAMP}-off"
  if [[ "$RESP_CODE" == "403" ]]; then
    verdict 1 "停用后调用被拒（403 且在查询前）" "code=403" "$HTTP_STATUS" "$RESP_CODE" ""
  else
    verdict 0 "停用后调用被拒（403 且在查询前）" "code=403" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
  request POST "${APPS}/${APP_A}/status?revision=${APP_A_REVISION}&enabled=true" "" "$AUTH"
  APP_A_DISABLED=0
  APP_A_REVISION=$(jget '.data.revision // 0')
else
  verdict 0 "停用应用" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# ------------------------------------------------- 11. 回收授权
step "11/14 回收授权后查询前拒绝"
if [[ -n "$GRANT_A" ]]; then
  request POST "${APPS}/${APP_A}/grants/${GRANT_A}/revoke" "" "$AUTH"
  if [[ "$RESP_CODE" == "0" ]]; then
    request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "Bearer $TOKEN_A_ROTATED" "X-Request-Id: prc-${STAMP}-rvk"
    if [[ "$RESP_CODE" == "403" ]]; then
      verdict 1 "回收授权后调用被拒（403）" "code=403" "$HTTP_STATUS" "$RESP_CODE" ""
    else
      verdict 0 "回收授权后调用被拒（403）" "code=403" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
    fi
    # 重新授权后恢复
    request POST "${APPS}/${APP_A}/grants" \
      "$(jq -n --arg c "$API_CODE" --argjson v "$API_VERSION" --argjson q "$QUOTA_A" \
         '{apiCode:$c,version:$v,quotaPerMinute:$q}')" "$AUTH"
    GRANT_A=$(jget '.data.id // empty')
    if [[ "$RESP_CODE" == "0" && -n "$GRANT_A" ]]; then
      verdict 1 "重新授权复用同一行并恢复" "code=0" "$HTTP_STATUS" "$RESP_CODE" ""
    else
      verdict 0 "重新授权复用同一行并恢复" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
    fi
  else
    verdict 0 "回收授权" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
else
  verdict 0 "回收授权" "存在可回收授权" "-" "-" "授权未创建"
fi

# --------------------------------------- 12. 按 requestId 追审计
step "12/14 按 requestId 追溯授权结果、API 版本与发布证据"
request GET "${AUDITS}?requestId=prc-${STAMP}-ok-1" "" "$AUTH"
AUDIT_TOTAL=$(jget '.data.total // 0')
AUDIT_APP=$(jget '.data.records[0].appId // empty')
AUDIT_CODE=$(jget '.data.records[0].apiCode // empty')
AUDIT_VER=$(jget '.data.records[0].apiVersion // empty')
AUDIT_PUB=$(jget '.data.records[0].publicationId // empty')
if [[ "$RESP_CODE" == "0" && "$AUDIT_TOTAL" == "1" && "$AUDIT_APP" == "$APP_A" \
      && "$AUDIT_CODE" == "$API_CODE" && "$AUDIT_VER" == "$API_VERSION" && -n "$AUDIT_PUB" ]]; then
  verdict 1 "审计可按 requestId 追溯" "appId+版本+publicationId" "$HTTP_STATUS" "$RESP_CODE" "pub=$AUDIT_PUB"
else
  verdict 0 "审计可按 requestId 追溯" "appId+版本+publicationId" "$HTTP_STATUS" "$RESP_CODE" \
    "total=$AUDIT_TOTAL app=$AUDIT_APP code=$AUDIT_CODE ver=$AUDIT_VER pub=$AUDIT_PUB"
fi

request GET "${AUDITS}?requestId=prc-${STAMP}-over" "" "$AUTH"
AUDIT_REJECT=$(jget '.data.records[0].resultCode // empty')
if [[ "$RESP_CODE" == "0" && "$AUDIT_REJECT" == "429" ]]; then
  verdict 1 "超额调用同样留痕（resultCode=429）" "resultCode=429" "$HTTP_STATUS" "$RESP_CODE" ""
else
  verdict 0 "超额调用同样留痕（resultCode=429）" "resultCode=429" "$HTTP_STATUS" "$RESP_CODE" "got=$AUDIT_REJECT"
fi

request GET "${AUDITS}?from=${BIZ_DATE}&to=${BIZ_DATE}" "" "$AUTH"
if [[ "$RESP_CODE" == "0" ]]; then
  verdict 1 "审计按项目与日期分页" "code=0" "$HTTP_STATUS" "$RESP_CODE" "total=$(jget '.data.total // 0')"
else
  verdict 0 "审计按项目与日期分页" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# --------------------------------------- 13. 旧签发人委派凭据兼容
step "13/14 旧签发人委派凭据仍可用（PR A 兼容）"
request POST "${VERSION_PATH}/credentials" \
  "$(jq -n --arg n "verify-legacy" --argjson t "$TTL_HOURS" '{appName:$n,ttlHours:$t}')" "$AUTH"
TOKEN_LEGACY=$(jget '.data.token // empty')
TOKEN_LEGACY_ID=$(jget '.data.credential.id // empty')
LEGACY_APP=$(jget '.data.credential.appId // empty')
if [[ "$RESP_CODE" == "0" && -n "$TOKEN_LEGACY" && -z "$LEGACY_APP" ]]; then
  verdict 1 "签发委派凭据（appId 为空）" "code=0 且 appId 为空" "$HTTP_STATUS" "$RESP_CODE" ""
  request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "Bearer $TOKEN_LEGACY" "X-Request-Id: prc-${STAMP}-legacy"
  if [[ "$RESP_CODE" == "0" ]]; then
    verdict 1 "委派凭据可调用（不受应用配额约束）" "code=0" "$HTTP_STATUS" "$RESP_CODE" ""
  else
    verdict 0 "委派凭据可调用（不受应用配额约束）" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
  fi
else
  verdict 0 "签发委派凭据（appId 为空）" "code=0 且 appId 为空" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# ------------------------------------------------- 14. 收尾
step "14/14 收尾（吊销凭据、回收授权、下线 API）"
CLEAN_OK=1
revoke_token "$APP_A" "$TOKEN_A_ID"
[[ "$RESP_CODE" == "0" ]] || CLEAN_OK=0
revoke_token "$APP_A" "$TOKEN_A_ROTATED_ID"
[[ "$RESP_CODE" == "0" ]] || CLEAN_OK=0
revoke_token "$APP_B" "$TOKEN_B_ID"
[[ "$RESP_CODE" == "0" ]] || CLEAN_OK=0
revoke_token "" "$TOKEN_LEGACY_ID"
[[ "$RESP_CODE" == "0" ]] || CLEAN_OK=0
verdict "$CLEAN_OK" "本次签发的凭据全部吊销" "code=0" "$HTTP_STATUS" "$RESP_CODE" ""

request GET "${DATA_PATH}?bizDate=${BIZ_DATE}" "" "Bearer $TOKEN_LEGACY" "X-Request-Id: prc-${STAMP}-after"
if [[ "$RESP_CODE" == "401" ]]; then
  verdict 1 "吊销后立即拒绝" "code=401" "$HTTP_STATUS" "$RESP_CODE" ""
else
  verdict 0 "吊销后立即拒绝" "code=401" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

if [[ -n "$GRANT_A" ]]; then
  request POST "${APPS}/${APP_A}/grants/${GRANT_A}/revoke" "" "$AUTH" >/dev/null 2>&1 || true
fi
if [[ -n "$GRANT_B" ]]; then
  request POST "${APPS}/${APP_B}/grants/${GRANT_B}/revoke" "" "$AUTH" >/dev/null 2>&1 || true
fi
request POST "${VERSION_PATH}/offline?revision=${CURRENT_REVISION}" "" "$AUTH"
if [[ "$RESP_CODE" == "0" ]]; then
  verdict 1 "临时 API 已下线" "code=0" "$HTTP_STATUS" "$RESP_CODE" ""
else
  verdict 0 "临时 API 已下线" "code=0" "$HTTP_STATUS" "$RESP_CODE" "$(jget '.msg // empty')"
fi

# ---------------------------------------------------------------- 汇总
finish 0
