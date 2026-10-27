#!/usr/bin/env bash
set -Eeuo pipefail
output=${1:?output CSV required}
: "${VAN_JAVA_PID:?dedicated VAN Java PID required}"
printf 'observed_at_utc,source,id,cpu_pct,rss_kb,memory_usage\n' > "$output"
while true; do
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  ps -p "$VAN_JAVA_PID" -o '%cpu=,rss=' |
    awk -v now="$now" -v pid="$VAN_JAVA_PID" '{printf "%s,host_van_java,%s,%s,%s,\n",now,pid,$1,$2}' >> "$output"
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' payment-postgres van-postgres |
    awk -F, -v now="$now" '{printf "%s,colima_postgres,%s,%s,,%s\n",now,$1,$2,$3}' >> "$output"
  sleep 1
done
