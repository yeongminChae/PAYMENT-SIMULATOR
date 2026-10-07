#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage: run-same-key-contention.sh --concurrency 20|50|100|200 [options]

Options:
  --repeat N              Run N fresh-posTrx trials (default: 1).
  --base-url URL          Payment API base URL (default: http://localhost:8080).
  --amount AMOUNT         Approval amount (default: 10000).
  --payment-db-url URL    psql URL (default: postgresql://postgres:postgres@localhost:5432/payment_sim).
  --van-db-url URL        psql URL (default: postgresql://postgres:postgres@localhost:5433/van_sim).
  --van-log-file PATH     VAN application log (default: ./logs/van-simulator.log).
  --sample-interval SEC   pg_stat_activity interval (default: 0.25).
  --pos-trx VALUE         Explicit posTrx; allowed only when --repeat 1.
EOF
}

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
ev_dir="$root_dir/engineering-validation/ev01"
concurrency=''
repeat=1
base_url=${BASE_URL:-http://localhost:8080}
amount=${AMOUNT:-10000}
payment_db_url=${PAYMENT_DB_URL:-postgresql://postgres:postgres@localhost:5432/payment_sim}
van_db_url=${VAN_DB_URL:-postgresql://postgres:postgres@localhost:5433/van_sim}
van_log_file=${VAN_LOG_FILE:-$root_dir/logs/van-simulator.log}
sample_interval=${SAMPLE_INTERVAL_SECONDS:-0.25}
explicit_pos_trx=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --concurrency) concurrency=${2:?}; shift 2;;
    --repeat) repeat=${2:?}; shift 2;;
    --base-url) base_url=${2:?}; shift 2;;
    --amount) amount=${2:?}; shift 2;;
    --payment-db-url) payment_db_url=${2:?}; shift 2;;
    --van-db-url) van_db_url=${2:?}; shift 2;;
    --van-log-file) van_log_file=${2:?}; shift 2;;
    --sample-interval) sample_interval=${2:?}; shift 2;;
    --pos-trx) explicit_pos_trx=${2:?}; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 64;;
  esac
done

case "$concurrency" in 20|50|100|200) ;; *) echo '--concurrency must be 20, 50, 100, or 200' >&2; exit 64;; esac
case "$repeat" in ''|*[!0-9]*|0) echo '--repeat must be a positive integer' >&2; exit 64;; esac
if [[ -n "$explicit_pos_trx" && "$repeat" != 1 ]]; then echo '--pos-trx requires --repeat 1' >&2; exit 64; fi

for command in k6 psql git; do command -v "$command" >/dev/null || { echo "required command not found: $command" >&2; exit 69; }; done
[[ -r "$van_log_file" ]] || { echo "VAN log is not readable: $van_log_file" >&2; exit 66; }
psql "$payment_db_url" -X -q -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null
psql "$van_db_url" -X -q -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null

new_pos_trx() {
  local candidate payment_used van_used
  for _ in $(seq 1 50); do
    candidate="2376-$(date -u +%Y%m%d)-$(printf '%04d' "$concurrency")-$(printf '%04d' "$((RANDOM % 10000))")"
    payment_used=$(psql "$payment_db_url" -X -q -A -t -v ON_ERROR_STOP=1 -c "SELECT EXISTS (SELECT 1 FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = '$candidate')")
    van_used=$(psql "$van_db_url" -X -q -A -t -v ON_ERROR_STOP=1 -c "SELECT EXISTS (SELECT 1 FROM van_approval WHERE pos_trx = '$candidate')")
    if [[ "$payment_used" == f && "$van_used" == f ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  echo 'could not generate an unused posTrx after 50 attempts' >&2
  return 1
}

for ((trial=1; trial<=repeat; trial++)); do
  run_id="$(date -u +%Y%m%dT%H%M%SZ)-c${concurrency}-r${trial}"
  result_dir="$ev_dir/results/A/$run_id"
  mkdir -p "$result_dir"
  [[ -z "$(find "$result_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]] || { echo "result directory already exists: $result_dir" >&2; exit 73; }
  pos_trx=${explicit_pos_trx:-"$(new_pos_trx)"}
  [[ "$pos_trx" =~ ^[0-9]{4}-[0-9]{8}-[0-9]{4}-[0-9]{4}$ ]] || { echo "invalid posTrx format: $pos_trx" >&2; exit 64; }
  payment_used=$(psql "$payment_db_url" -X -q -A -t -v ON_ERROR_STOP=1 -c "SELECT EXISTS (SELECT 1 FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = '$pos_trx')")
  van_used=$(psql "$van_db_url" -X -q -A -t -v ON_ERROR_STOP=1 -c "SELECT EXISTS (SELECT 1 FROM van_approval WHERE pos_trx = '$pos_trx')")
  [[ "$payment_used" == f && "$van_used" == f ]] || { echo "posTrx is not fresh in Payment or VAN DB: $pos_trx" >&2; exit 65; }
  sampler_pid=''
  cleanup() { [[ -z "$sampler_pid" ]] || { kill "$sampler_pid" 2>/dev/null || true; wait "$sampler_pid" 2>/dev/null || true; }; }
  trap cleanup EXIT INT TERM

  {
    echo "phase=A"
    echo "run_id=$run_id"
    echo "pos_trx=$pos_trx"
    echo "concurrency=$concurrency"
    echo "repeat_index=$trial"
    echo "base_url=$base_url"
    echo "amount=$amount"
    echo "payment_db_url=provided (intentionally redacted)"
    echo "van_db_url=provided (intentionally redacted)"
    echo "van_log_file=$van_log_file"
    echo "sample_interval_seconds=$sample_interval"
    echo "git_commit=$(git -C "$root_dir" rev-parse HEAD)"
    echo "git_branch=$(git -C "$root_dir" branch --show-current)"
    echo "k6_version=$(k6 version)"
    echo "psql_version=$(psql --version)"
    echo "started_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$result_dir/environment.txt"

  "$ev_dir/scripts/sample-payment-db-waits.sh" "$payment_db_url" "$result_dir/db-waits.csv" "$sample_interval" 2>"$result_dir/db-waits.stderr" &
  sampler_pid=$!
  sleep 0.1
  kill -0 "$sampler_pid" || { cat "$result_dir/db-waits.stderr" >&2; exit 1; }

  start_epoch=$(date +%s)
  if ! BASE_URL="$base_url" POS_TRX="$pos_trx" VUS="$concurrency" AMOUNT="$amount" SUMMARY_PATH="$result_dir/k6-summary.json" \
      k6 run "$ev_dir/k6/same-key-contention.js" >"$result_dir/k6-output.txt" 2>&1; then
    echo "k6 failed; see $result_dir/k6-output.txt" >&2
    exit 1
  fi
  end_epoch=$(date +%s)
  cleanup; sampler_pid=''
  [[ -s "$result_dir/db-waits.csv" && $(wc -l < "$result_dir/db-waits.csv") -gt 1 ]] || { echo 'DB sampler produced no samples' >&2; exit 1; }
  [[ ! -s "$result_dir/db-waits.stderr" ]] || { echo "DB sampler reported an error; see $result_dir/db-waits.stderr" >&2; exit 1; }

  psql "$payment_db_url" -X -v ON_ERROR_STOP=1 -v "pos_trx=$pos_trx" -f "$ev_dir/sql/assert-contention-payment.sql" > "$result_dir/payment-assertion.txt"
  psql "$van_db_url" -X -v ON_ERROR_STOP=1 -v "pos_trx=$pos_trx" -f "$ev_dir/sql/assert-contention-van.sql" > "$result_dir/van-assertion.txt"
  received_count=$(
    { grep -F '[van-tcp][approval][received]' "$van_log_file" || true; } |
      { grep -F "posTrx=$pos_trx" || true; } |
      wc -l | tr -d ' '
  )
  {
    echo "pos_trx=$pos_trx"
    echo "matching_received_log_count=$received_count"
    echo "expected=1"
  } > "$result_dir/van-log-assertion.txt"
  [[ "$received_count" == 1 ]] || { echo "VAN received log count was $received_count, expected 1" >&2; exit 1; }

  {
    echo "status=PASS"
    echo "phase=A"
    echo "run_id=$run_id"
    echo "pos_trx=$pos_trx"
    echo "concurrency=$concurrency"
    echo "wall_clock_seconds=$((end_epoch - start_epoch))"
    echo "performance_evidence=k6-summary.json"
    echo "correctness_evidence=payment-assertion.txt,van-assertion.txt,van-log-assertion.txt"
    echo "wait_samples=db-waits.csv (point-in-time pg_stat_activity observations; not cumulative lock wait duration)"
  } > "$result_dir/run-summary.txt"
  trap - EXIT INT TERM
  echo "PASS: $result_dir"
done
