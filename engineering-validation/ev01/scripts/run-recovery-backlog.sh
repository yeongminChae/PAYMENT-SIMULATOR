#!/usr/bin/env bash
set -Eeuo pipefail
# C는 fixture 쓰기 권한을 명시적으로 받은 전용 DB에서만 실행한다.
ev_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
command -v python3 >/dev/null || { echo 'python3 required' >&2; exit 69; }
exec python3 "$ev_dir/scripts/recovery-backlog.py" "$@"
