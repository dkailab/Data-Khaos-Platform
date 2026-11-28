#!/usr/bin/env bash
# Exercises an already-bound test DAG. The DAG should have no production side effects.
set -euo pipefail

: "${DK_TOKEN:?set a Data Khaos token with develop and schedule permissions}"
: "${DK_WORKFLOW_ID:?set the bound test workflow ID}"
: "${DK_NODE_CODE:?set the bound DolphinScheduler node code}"
: "${DK_FROM_DATE:?set the first test business date}"
: "${DK_TO_DATE:?set the following test business date}"
: "${DS_BASE_URL:?set the DolphinScheduler API root URL}"
: "${DS_TOKEN:?set a DolphinScheduler API token}"
: "${DS_PROJECT_CODE:?set the DolphinScheduler project code}"

gateway=${DK_GATEWAY:-http://127.0.0.1:8099}
workflow_url="$gateway/api/workflow/$DK_WORKFLOW_ID"
if ! jq -en --arg from "$DK_FROM_DATE" --arg to "$DK_TO_DATE" '
  def epoch: strptime("%Y-%m-%d") | mktime;
  ($to | epoch) - ($from | epoch) == 86400
' >/dev/null; then
  echo '验收只接受相邻两个业务日期。' >&2
  exit 2
fi
request_id="ds-pilot-$(uuidgen | tr '[:upper:]' '[:lower:]')"

dk() {
  curl -fsS --max-time 15 -H "Authorization: Bearer $DK_TOKEN" "$@" |
    jq -ec 'if .code == 0 then .data else error(.msg // "Data Khaos API rejected request") end'
}

remote_id() {
  dk "$gateway/api/workflow/run/$1/nodes" |
    jq -er --arg node "$DK_NODE_CODE" '
      [.[] | select(.nodeCode == $node and .refType == "DOLPHINSCHEDULER")]
      | select(length == 1) | .[0].refId | select(test("^[1-9][0-9]*$"))'
}

verify_run() {
  local run_id="$1" expected_date="$2" status instance attempt
  for ((attempt = 0; attempt < 120; attempt++)); do
    status=$(dk "$gateway/api/workflow/run/$run_id" |
      jq -er --arg day "$expected_date" 'select(.bizDate == $day) | .runStatus') || return 1
    case "$status" in
      SUCCESS) break ;;
      FAILED|STOP|RECOVERY_REQUIRED) echo "本地运行 $run_id 状态 $status" >&2; return 1 ;;
    esac
    sleep 2
  done
  if [[ "$status" != SUCCESS ]]; then
    echo "等待本地运行 $run_id 超时" >&2
    return 1
  fi
  instance=$(remote_id "$run_id") || return 1
  curl -fsS --max-time 10 -H "token: $DS_TOKEN" \
    "${DS_BASE_URL%/}/projects/$DS_PROJECT_CODE/workflow-instances/$instance" |
      jq -e '.code == 0 and .data.state == "SUCCESS"' >/dev/null || return 1
  printf '%s\n' "$instance"
}

payload=$(jq -nc --arg id "$request_id" --arg from "$DK_FROM_DATE" --arg to "$DK_TO_DATE" \
  '{requestId:$id,fromDate:$from,toDate:$to}')
batch=$(dk -H 'Content-Type: application/json' -d "$payload" "$workflow_url/backfills")
first=$(jq -er --arg date "$DK_FROM_DATE" '.items[] | select(.bizDate == $date and .state == "ACCEPTED") | .runId' <<<"$batch")
second=$(jq -er --arg date "$DK_TO_DATE" '.items[] | select(.bizDate == $date and .state == "ACCEPTED") | .runId' <<<"$batch")
jq -e '.items | length == 2' <<<"$batch" >/dev/null
[[ "$first" != "$second" ]]
first_instance=$(verify_run "$first" "$DK_FROM_DATE")
second_instance=$(verify_run "$second" "$DK_TO_DATE")
[[ "$first_instance" != "$second_instance" ]]

replay=$(dk -H 'Content-Type: application/json' -d "$payload" "$workflow_url/backfills")
jq -e --arg first "$first" --arg second "$second" \
  '.items | length == 2 and all(.[]; .state == "DUPLICATE") and map(.runId) == [$first, $second]' \
  <<<"$replay" >/dev/null
[[ "$(remote_id "$first")" == "$first_instance" && "$(remote_id "$second")" == "$second_instance" ]]
printf '通过：两日补数对应不同远端实例 %s 和 %s，重放返回原运行。\n' "$first_instance" "$second_instance"
