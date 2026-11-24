[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet(20, 50, 100, 200)][int]$Concurrency,
  [ValidateRange(1, 100)][int]$Repeat = 1,
  [string]$BaseUrl = $(if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' }),
  [int]$Amount = $(if ($env:AMOUNT) { [int]$env:AMOUNT } else { 10000 }),
  [string]$PaymentDbUrl = $(if ($env:PAYMENT_DB_URL) { $env:PAYMENT_DB_URL } else { 'postgresql://postgres:postgres@localhost:5432/payment_sim' }),
  [string]$VanDbUrl = $(if ($env:VAN_DB_URL) { $env:VAN_DB_URL } else { 'postgresql://postgres:postgres@localhost:5433/van_sim' }),
  [string]$VanLogFile = $(if ($env:VAN_LOG_FILE) { $env:VAN_LOG_FILE } else { (Join-Path (Get-Location) 'logs/van-simulator.log') }),
  [ValidateRange(0.05, 60)][double]$SampleIntervalSeconds = 0.25,
  [string]$PosTrx
)

$ErrorActionPreference = 'Stop'
if ($Amount -lt 1) { throw 'Amount must be positive.' }
if ($PosTrx -and $Repeat -ne 1) { throw '-PosTrx requires -Repeat 1.' }
foreach ($command in 'k6', 'psql', 'git') { if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required command not found: $command" } }
if (-not (Test-Path -LiteralPath $VanLogFile -PathType Leaf)) { throw "VAN log is not readable: $VanLogFile" }
& psql $PaymentDbUrl -X -q -v ON_ERROR_STOP=1 -c 'SELECT 1' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Payment DB connectivity check failed.' }
& psql $VanDbUrl -X -q -v ON_ERROR_STOP=1 -c 'SELECT 1' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'VAN DB connectivity check failed.' }

function New-Ev01PosTrx {
  for ($attempt = 1; $attempt -le 50; $attempt++) {
    $candidate = "2376-{0}-{1:D4}-{2:D4}" -f (Get-Date).ToUniversalTime().ToString('yyyyMMdd'), $Concurrency, (Get-Random -Minimum 0 -Maximum 10000)
    $paymentUsed = (& psql $PaymentDbUrl -X -q -A -t -v ON_ERROR_STOP=1 -c "SELECT EXISTS (SELECT 1 FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = '$candidate')").Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Payment DB fresh-posTrx check failed.' }
    $vanUsed = (& psql $VanDbUrl -X -q -A -t -v ON_ERROR_STOP=1 -c "SELECT EXISTS (SELECT 1 FROM van_approval WHERE pos_trx = '$candidate')").Trim()
    if ($LASTEXITCODE -ne 0) { throw 'VAN DB fresh-posTrx check failed.' }
    if ($paymentUsed -eq 'f' -and $vanUsed -eq 'f') { return $candidate }
  }
  throw 'Could not generate an unused posTrx after 50 attempts.'
}

function Get-LogPrefixChecksum {
  param([string]$Path, [long]$Bytes)
  if ($Bytes -eq 0) { return 'empty' }
  $stream = [IO.File]::OpenRead($Path)
  try {
    $buffer = New-Object byte[] $Bytes
    $read = $stream.Read($buffer, 0, $buffer.Length)
    if ($read -ne $Bytes) { throw 'VAN log changed while reading its baseline prefix.' }
    return ([BitConverter]::ToString(([Security.Cryptography.SHA256]::Create()).ComputeHash($buffer))).Replace('-', '')
  } finally { $stream.Dispose() }
}

$evDir = Split-Path -Parent $PSScriptRoot
$rootDir = Resolve-Path (Join-Path $evDir '../..')
for ($trial = 1; $trial -le $Repeat; $trial++) {
  $runId = "{0}-c{1}-r{2}" -f (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'), $Concurrency, $trial
  $resultDir = Join-Path $evDir "results/A/$runId"
  New-Item -ItemType Directory -Path $resultDir -ErrorAction Stop | Out-Null
  $currentPosTrx = if ($PosTrx) { $PosTrx } else { New-Ev01PosTrx }
  if ($currentPosTrx -notmatch '^\d{4}-\d{8}-\d{4}-\d{4}$') { throw "Invalid posTrx format: $currentPosTrx" }
  $paymentUsed = (& psql $PaymentDbUrl -X -q -A -t -v ON_ERROR_STOP=1 -c "SELECT EXISTS (SELECT 1 FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = '$currentPosTrx')").Trim()
  if ($LASTEXITCODE -ne 0) { throw 'Payment DB fresh-posTrx check failed.' }
  $vanUsed = (& psql $VanDbUrl -X -q -A -t -v ON_ERROR_STOP=1 -c "SELECT EXISTS (SELECT 1 FROM van_approval WHERE pos_trx = '$currentPosTrx')").Trim()
  if ($LASTEXITCODE -ne 0) { throw 'VAN DB fresh-posTrx check failed.' }
  if ($paymentUsed -ne 'f' -or $vanUsed -ne 'f') { throw "posTrx is not fresh in Payment or VAN DB: $currentPosTrx" }
  $logInfo = Get-Item -LiteralPath $VanLogFile
  $logBaselineCreation = $logInfo.CreationTimeUtc.Ticks
  $logBaselineBytes = $logInfo.Length
  $logBaselineLines = @(Get-Content -LiteralPath $VanLogFile).Count
  $logBaselineChecksum = Get-LogPrefixChecksum -Path $VanLogFile -Bytes $logBaselineBytes
  $environment = @(
    'phase=A', "run_id=$runId", "pos_trx=$currentPosTrx", "concurrency=$Concurrency", "repeat_index=$trial", "base_url=$BaseUrl", "amount=$Amount",
    "van_log_file=$VanLogFile", "van_log_baseline_creation_ticks=$logBaselineCreation", "van_log_baseline_bytes=$logBaselineBytes", "van_log_baseline_lines=$logBaselineLines", "van_log_baseline_prefix_checksum=$logBaselineChecksum", "sample_interval_seconds=$SampleIntervalSeconds", "git_commit=$(& git -C $rootDir rev-parse HEAD)", "git_branch=$(& git -C $rootDir branch --show-current)",
    "k6_version=$(& k6 version)", "psql_version=$(& psql --version)", "started_at_utc=$((Get-Date).ToUniversalTime().ToString('o'))"
  )
  Set-Content -LiteralPath (Join-Path $resultDir 'environment.txt') -Value $environment
  $csv = Join-Path $resultDir 'db-waits.csv'
  Set-Content -LiteralPath $csv -Value 'observed_at_utc,active_connection_count,wait_event_type,wait_event,lock_wait_observed'
  $query = "WITH active AS (SELECT wait_event_type, wait_event FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND state = 'active'), sample AS (SELECT count(*) AS active_connection_count FROM active) SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD`"T`"HH24:MI:SS.MS`"Z`"'), sample.active_connection_count, coalesce(active.wait_event_type, ''), coalesce(active.wait_event, ''), CASE WHEN active.wait_event_type = 'Lock' THEN 'true' ELSE 'false' END FROM sample LEFT JOIN active ON true;"
  $sampler = Start-Job -ScriptBlock { param($db, $output, $seconds, $sql) while ($true) { & psql $db -X -q -A -t -F ',' -v ON_ERROR_STOP=1 -c $sql >> $output; if ($LASTEXITCODE -ne 0) { throw "pg_stat_activity sampler psql exited with $LASTEXITCODE" }; Start-Sleep -Milliseconds ([int]($seconds * 1000)) } } -ArgumentList $PaymentDbUrl, $csv, $SampleIntervalSeconds, $query
  try {
    Start-Sleep -Milliseconds 100
    if ($sampler.State -ne 'Running') { Receive-Job $sampler; throw 'DB sampler exited before k6 started.' }
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $env:BASE_URL = $BaseUrl; $env:POS_TRX = $currentPosTrx; $env:VUS = "$Concurrency"; $env:AMOUNT = "$Amount"; $env:SUMMARY_PATH = (Join-Path $resultDir 'k6-summary.json')
    & k6 run (Join-Path $evDir 'k6/same-key-contention.js') *> (Join-Path $resultDir 'k6-output.txt')
    if ($LASTEXITCODE -ne 0) { throw "k6 failed; see $(Join-Path $resultDir 'k6-output.txt')" }
    $stopwatch.Stop()
    $samplerErrors = @($sampler.ChildJobs | ForEach-Object { $_.Error } | ForEach-Object { $_.ToString() })
    if ($sampler.State -eq 'Failed' -or $sampler.ChildJobs.JobStateInfo.State -contains 'Failed' -or $samplerErrors.Count -gt 0) {
      throw "DB sampler failed during k6: $($samplerErrors -join '; ')"
    }
  } finally { Stop-Job $sampler -ErrorAction SilentlyContinue; Receive-Job $sampler -ErrorAction SilentlyContinue | Out-Null; Remove-Job $sampler -Force -ErrorAction SilentlyContinue }
  if ((Get-Content -LiteralPath $csv).Count -le 1) { throw 'DB sampler produced no samples.' }
  $logAfter = Get-Item -LiteralPath $VanLogFile
  if ($logAfter.CreationTimeUtc.Ticks -ne $logBaselineCreation) { throw 'VAN log rotated/replaced during run; refusing assertion.' }
  if ($logAfter.Length -lt $logBaselineBytes) { throw 'VAN log was truncated during run; refusing assertion.' }
  if ((Get-LogPrefixChecksum -Path $VanLogFile -Bytes $logBaselineBytes) -ne $logBaselineChecksum) { throw 'VAN log prefix changed during run; refusing assertion.' }
  Get-Content -LiteralPath $VanLogFile | Select-Object -Skip $logBaselineLines | Set-Content -LiteralPath (Join-Path $resultDir 'van-log-delta.txt')
  & psql $PaymentDbUrl -X -v ON_ERROR_STOP=1 -v "pos_trx=$currentPosTrx" -f (Join-Path $evDir 'sql/assert-contention-payment.sql') | Set-Content (Join-Path $resultDir 'payment-assertion.txt')
  if ($LASTEXITCODE -ne 0) { throw 'Payment assertion failed.' }
  & psql $VanDbUrl -X -v ON_ERROR_STOP=1 -v "pos_trx=$currentPosTrx" -f (Join-Path $evDir 'sql/assert-contention-van.sql') | Set-Content (Join-Path $resultDir 'van-assertion.txt')
  if ($LASTEXITCODE -ne 0) { throw 'VAN assertion failed.' }
  $receivedCount = @(Select-String -LiteralPath (Join-Path $resultDir 'van-log-delta.txt') -SimpleMatch '[van-tcp][approval][received]' | Where-Object { $_.Line.Contains("posTrx=$currentPosTrx") }).Count
  Set-Content -LiteralPath (Join-Path $resultDir 'van-log-assertion.txt') -Value @("pos_trx=$currentPosTrx", "matching_received_log_count=$receivedCount", 'expected=1')
  if ($receivedCount -ne 1) { throw "VAN received log count was $receivedCount, expected 1." }
  Set-Content -LiteralPath (Join-Path $resultDir 'run-summary.txt') -Value @('status=PASS', 'phase=A', "run_id=$runId", "pos_trx=$currentPosTrx", "concurrency=$Concurrency", "wall_clock_seconds=$([math]::Round($stopwatch.Elapsed.TotalSeconds, 3))", 'performance_evidence=k6-summary.json', 'correctness_evidence=payment-assertion.txt,van-assertion.txt,van-log-assertion.txt', 'wait_samples=db-waits.csv (point-in-time pg_stat_activity observations; not cumulative lock wait duration)')
  Write-Host "PASS: $resultDir"
}
