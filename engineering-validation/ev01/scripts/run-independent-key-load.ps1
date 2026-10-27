[CmdletBinding()]
param(
  [ValidateSet(10,25,50,100,200)][int]$Vus = 10,
  [ValidateRange(1,100)][int]$Repeat = 3,
  [switch]$Smoke,
  [string]$BaseUrl = 'http://localhost:8080',
  [int]$Amount = 10000,
  [string]$PaymentDbUrl = 'postgresql://postgres:postgres@localhost:5432/payment_sim',
  [string]$VanDbUrl = 'postgresql://postgres:postgres@localhost:5433/van_sim',
  [string]$VanLogFile = "$PWD/logs/van-simulator.log",
  [double]$SampleIntervalSeconds = 0.25,
  [int]$SettleSeconds = 2
)
$ErrorActionPreference = 'Stop'
# 동일 예약/검증 경로를 사용한다. Windows는 Git Bash의 python3/psql/k6도 PATH에 필요하다.
if (-not (Get-Command bash -ErrorAction SilentlyContinue)) { throw 'Git Bash is required.' }
$arguments = @((Join-Path $PSScriptRoot 'run-independent-key-load.sh').Replace('\','/'),
  '--vus', "$Vus", '--repeat', "$Repeat", '--base-url', $BaseUrl, '--amount', "$Amount",
  '--payment-db-url', $PaymentDbUrl, '--van-db-url', $VanDbUrl,
  '--van-log-file', $VanLogFile.Replace('\','/'),
  '--sample-interval', $SampleIntervalSeconds.ToString([Globalization.CultureInfo]::InvariantCulture),
  '--settle-seconds', "$SettleSeconds")
if ($Smoke) { $arguments += '--smoke' }
& bash @arguments
if ($LASTEXITCODE -ne 0) { throw "B harness failed with exit code $LASTEXITCODE; inspect preserved run evidence." }
