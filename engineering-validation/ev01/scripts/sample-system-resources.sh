#!/usr/bin/env bash
set -Eeuo pipefail
output=${1:?output csv required}
interval=${2:?interval required}
: "${PAYMENT_JAVA_PID:?host Payment Java PID required}"
: "${VAN_JAVA_PID:?host VAN Java PID required}"
printf 'observed_at_utc,source,id,cpu_pct,rss_kb,memory_usage\n' > "$output"
while true; do
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  for item in "payment:$PAYMENT_JAVA_PID" "van:$VAN_JAVA_PID"; do
    pid=${item##*:}; name=${item%%:*}
    stats=$(ps -p "$pid" -o '%cpu=,rss=')
    [[ -n "$stats" ]] || { echo "Java PID disappeared: $pid" >&2; exit 1; }
    echo "$stats" | awk -v now="$now" -v name="$name" -v pid="$pid" '{printf "%s,host_%s_java,%s,%s,%s,\n",now,name,pid,$1,$2}' >> "$output"
  done
  # 선택한 Docker context가 Colima인지 wrapper에서 확인한다. PostgreSQL 두 컨테이너만 수집한다.
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' payment-postgres van-postgres |
    awk -F, -v now="$now" '{printf "%s,colima_postgres,%s,%s,,%s\n",now,$1,$2,$3}' >> "$output"
  sleep "$interval"
done
