#!/usr/bin/env bash
set -Eeuo pipefail

# Samples current pg_stat_activity state; it is not cumulative lock-wait duration.
if [[ $# -ne 3 ]]; then
  echo "usage: $0 <payment-db-url> <output-csv> <interval-seconds>" >&2
  exit 64
fi

payment_db_url=$1
output=$2
interval=$3

case "$interval" in
  ''|*[!0-9.]*|.) echo "interval-seconds must be a positive number" >&2; exit 64;;
esac

mkdir -p "$(dirname "$output")"
printf 'observed_at_utc,active_connection_count,wait_event_type,wait_event,lock_wait_observed\n' > "$output"

query="
WITH active AS (
  SELECT wait_event_type, wait_event
  FROM pg_stat_activity
  WHERE datname = current_database()
    AND pid <> pg_backend_pid()
    AND state = 'active'
), sample AS (
  SELECT count(*) AS active_connection_count FROM active
)
SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'),
       sample.active_connection_count,
       coalesce(active.wait_event_type, ''),
       coalesce(active.wait_event, ''),
       CASE WHEN active.wait_event_type = 'Lock' THEN 'true' ELSE 'false' END
FROM sample
LEFT JOIN active ON true;"

while true; do
  psql "$payment_db_url" -X -q -A -t -F ',' -v ON_ERROR_STOP=1 -c "$query" >> "$output"
  sleep "$interval"
done
