#!/usr/bin/env bash
set -u

usage() {
  echo "用法: bash scripts/test-fast.sh [--online] [Maven 模块列表]"
  echo "模块列表用逗号分隔，省略时测试整个 reactor。前端构建并行执行。"
}

offline=(-o)
if [[ ${1:-} == --online ]]; then
  offline=()
  shift
fi
if [[ ${1:-} == --help || ${1:-} == -h ]]; then
  usage
  exit 0
fi
if (( $# > 1 )) || [[ ${1:-} == -* ]]; then
  usage >&2
  exit 2
fi

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
modules=()
if (( $# == 1 )); then
  modules=(-pl "$1" -am)
fi
logs=$(mktemp -d "${TMPDIR:-/tmp}/data-khaos-test.XXXXXX") || exit 1

mvn -B -T 2 ${offline[@]+"${offline[@]}"} ${modules[@]+"${modules[@]}"} test > "$logs/backend.log" 2>&1 &
backend=$!
npm --prefix data-khaos-web run build > "$logs/frontend.log" 2>&1 &
frontend=$!
trap 'kill "$backend" "$frontend" 2>/dev/null || true; exit 130' INT TERM

echo "并行执行 Maven 测试和前端构建，日志目录 $logs"
backend_status=0
frontend_status=0
wait "$backend" || backend_status=$?
wait "$frontend" || frontend_status=$?
if (( backend_status == 0 && frontend_status == 0 )); then
  tail -n 6 "$logs/backend.log"
  tail -n 2 "$logs/frontend.log"
  exit 0
fi
if (( backend_status != 0 )); then
  tail -n 40 "$logs/backend.log" >&2
fi
if (( frontend_status != 0 )); then
  tail -n 40 "$logs/frontend.log" >&2
fi
exit 1
