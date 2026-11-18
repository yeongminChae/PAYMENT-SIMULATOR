#!/usr/bin/env bash
set -Eeuo pipefail

usage() { cat <<'EOF'
Usage: run-independent-key-load.sh --vus 10|25|50|100|200 [options]
  --repeat N              60-second trials per VU level (default: 3)
  --smoke                 Use 10 VU for 5 seconds and one trial
  --base-url URL          Payment API base URL (default: http://localhost:8080)
  --amount AMOUNT         Approval amount (default: 10000)
  --payment-db-url URL    Payment PostgreSQL URL
  --van-db-url URL        VAN PostgreSQL URL
  --van-log-file PATH     VAN application log path
  --sample-interval SEC   DB/system sample interval (default: 0.25)
  --settle-seconds SEC    Wait after k6 before assertions (default: 2)
EOF
}

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
ev_dir="$root_dir/engineering-validation/ev01"
vus=''; repeat=3; smoke=false; duration='60s'; amount=${AMOUNT:-10000}
base_url=${BASE_URL:-http://localhost:8080}
payment_db_url=${PAYMENT_DB_URL:-postgresql://postgres:postgres@localhost:5432/payment_sim}
van_db_url=${VAN_DB_URL:-postgresql://postgres:postgres@localhost:5433/van_sim}
van_log_file=${VAN_LOG_FILE:-$root_dir/logs/van-simulator.log}
sample_interval=${SAMPLE_INTERVAL_SECONDS:-0.25}; settle_seconds=${SETTLE_SECONDS:-2}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --vus) vus=${2:?}; shift 2;;
    --repeat) repeat=${2:?}; shift 2;;
    --smoke) smoke=true; shift;;
    --base-url) base_url=${2:?}; shift 2;;
    --amount) amount=${2:?}; shift 2;;
    --payment-db-url) payment_db_url=${2:?}; shift 2;;
    --van-db-url) van_db_url=${2:?}; shift 2;;
    --van-log-file) van_log_file=${2:?}; shift 2;;
    --sample-interval) sample_interval=${2:?}; shift 2;;
    --settle-seconds) settle_seconds=${2:?}; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 64;;
  esac
done

if [[ "$smoke" == true ]]; then vus=10; repeat=1; duration=5s; fi
case "$vus" in 10|25|50|100|200) ;; *) echo '--vus must be 10, 25, 50, 100, or 200' >&2; exit 64;; esac
case "$repeat" in ''|*[!0-9]*|0) echo '--repeat must be positive' >&2; exit 64;; esac
[[ "$amount" =~ ^[1-9][0-9]*$ ]] || { echo 'amount must be positive integer' >&2; exit 64; }
[[ "$sample_interval" =~ ^[0-9]+([.][0-9]+)?$ && "$settle_seconds" =~ ^[0-9]+([.][0-9]+)?$ ]] || exit 64
awk -v n="$sample_interval" 'BEGIN {exit !(n>0)}' || exit 64
: "${PAYMENT_JAVA_PID:?host Payment Java PID required}"
: "${VAN_JAVA_PID:?host VAN Java PID required}"
[[ "$PAYMENT_JAVA_PID" =~ ^[1-9][0-9]*$ && "$VAN_JAVA_PID" =~ ^[1-9][0-9]*$ && "$PAYMENT_JAVA_PID" != "$VAN_JAVA_PID" ]] || exit 64
for pid in "$PAYMENT_JAVA_PID" "$VAN_JAVA_PID"; do ps -p "$pid" -o comm= | grep -qi java || exit 64; done
docker context show | grep -q '^colima' || { echo 'Select Colima Docker context first' >&2; exit 64; }
docker stats --no-stream payment-postgres van-postgres >/dev/null
for command in k6 psql git python3; do command -v "$command" >/dev/null || { echo "required command not found: $command" >&2; exit 69; }; done
[[ -r "$van_log_file" ]] || { echo "VAN log is not readable: $van_log_file" >&2; exit 66; }
psql "$payment_db_url" -X -q -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null
psql "$van_db_url" -X -q -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null


# 같은 머신의 모든 checkout에서 같은 ledger를 공유한다. 기록은 삭제하지 않는다.
reservation_dir=${EV01_RESERVATION_DIR:-${HOME:?}/.payment-simulator/ev01-b-reservations}
evidence="$ev_dir/scripts/independent-key-evidence.py"
export PAYMENT_DB_URL="$payment_db_url" VAN_DB_URL="$van_db_url"
for ((trial=1; trial<=repeat; trial++)); do
  biz_date=$(date -u +%Y%m%d)
  pos_no_base=$(python3 "$evidence" reserve "$reservation_dir" "$biz_date" "$vus")
  # 예약 실패/DB 충돌은 HTTP 전송 전에 즉시 중단한다. 예약은 영구 보존한다.
  python3 "$evidence" fresh "$biz_date" "$pos_no_base"
  run_id="$(date -u +%Y%m%dT%H%M%SZ)-b${vus}-p${pos_no_base}-r${trial}"
  mkdir -p "$ev_dir/results/B"
  result_dir="$ev_dir/results/B/$run_id"
  mkdir "$result_dir"
  seconds=60; [[ "$smoke" == true ]] && seconds=5
  python3 "$evidence" checkpoint "$van_log_file" "$result_dir/environment.json" "$biz_date" "$pos_no_base" "$vus" "$seconds" "$settle_seconds"
  {
    echo "phase=B"; echo "run_id=$run_id"; echo "reservation_dir=$reservation_dir"
    echo "vus=$vus"; echo "duration=$duration"; echo "graceful_stop=15s"; echo "settle_seconds=$settle_seconds"
    echo "pos_no_base=$pos_no_base"; echo "reserved_pos_no_end=$((pos_no_base + 199))"
    echo "git_commit=$(git -C "$root_dir" rev-parse HEAD)"; echo "k6_version=$(k6 version)"
    echo "psql_version=$(psql --version)"; echo "amount=$amount"
  } > "$result_dir/environment.txt"
  : > "$result_dir/stage-status.txt"
  db_pid=''; system_pid=''
  cleanup() {
    for pid in "$db_pid" "$system_pid"; do
      [[ -z "$pid" ]] || { kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; }
    done
  }
  trap cleanup EXIT
  trap 'exit 130' INT TERM
  bash "$ev_dir/scripts/sample-payment-db-waits.sh" "$payment_db_url" "$result_dir/db-waits.csv" "$sample_interval" 2>"$result_dir/db-waits.stderr" & db_pid=$!
  bash "$ev_dir/scripts/sample-system-resources.sh" "$result_dir/system-resources.csv" "$sample_interval" 2>"$result_dir/system-resources.stderr" & system_pid=$!
  # 실행 실패 뒤에도 collect를 수행한다. 각 단계 실패는 종합 판정에 반영한다.
  k6_code=0
  BASE_URL="$base_url" STORE_CD=2376 BIZ_DATE="$biz_date" POS_NO_BASE="$pos_no_base" VUS="$vus" DURATION="$duration" AMOUNT="$amount" SUMMARY_PATH="$result_dir/k6-summary.json" \
    k6 run --log-format raw --console-output "$result_dir/request-events.jsonl" "$ev_dir/k6/independent-key-load.js" >"$result_dir/k6-output.txt" 2>&1 || k6_code=$?
  echo "k6_exit_code=$k6_code" >> "$result_dir/stage-status.txt"
  [[ "$k6_code" == 0 ]] || echo "k6=FAIL" >> "$result_dir/stage-status.txt"
  for pid in "$db_pid" "$system_pid"; do
    kill -0 "$pid" 2>/dev/null || echo "sampler_early_exit=FAIL" >> "$result_dir/stage-status.txt"
  done
  cleanup; db_pid=''; system_pid=''
  for name in db-waits system-resources; do
    [[ -f "$result_dir/$name.csv" && $(wc -l < "$result_dir/$name.csv") -gt 1 && ! -s "$result_dir/$name.stderr" ]] ||
      echo "$name=FAIL" >> "$result_dir/stage-status.txt"
  done
  sleep "$settle_seconds"
  collect_code=0
  python3 "$evidence" collect "$result_dir" "$ev_dir" > "$result_dir/evidence-output.txt" 2>&1 || collect_code=$?
  if [[ "$collect_code" != 0 ]]; then
    echo "evidence_or_correctness=FAIL" >> "$result_dir/stage-status.txt"
    [[ -f "$result_dir/run-summary.txt" ]] || printf 'status=FAIL\nevidence_collection=incomplete\n' > "$result_dir/run-summary.txt"
    echo "FAIL: $result_dir" >&2
    exit 1
  fi
  trap - EXIT INT TERM
  echo "PASS: $result_dir"
done
