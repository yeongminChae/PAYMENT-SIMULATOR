#!/usr/bin/env bash
set -Eeuo pipefail
# 예: bash run-recovery-backlog.sh --smoke. C DB opt-in과 PID 환경변수는 README에 설명한다.
# 별도 자식 셸을 유지하지 않고 Python의 signal/종료 코드 처리 경로로 넘긴다.
# C는 fixture 쓰기 권한을 명시적으로 받은 전용 DB에서만 실행한다.
ev_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
command -v python3 >/dev/null || { echo 'python3 required' >&2; exit 69; }
exec python3 "$ev_dir/scripts/recovery-backlog.py" "$@"
