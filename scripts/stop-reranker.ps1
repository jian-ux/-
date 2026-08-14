$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $repoRoot ".runtime\qwen3-vl-reranker\reranker.pid"

$serviceRoot = Join-Path $repoRoot "services\qwen3-vl-reranker"
$processes = Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object {
    $_.CommandLine -like '*-m uvicorn app:app*' -and
    $_.CommandLine -like "*$serviceRoot*"
}
foreach ($process in ($processes | Sort-Object ParentProcessId -Descending)) {
    Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
}
Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
if ($processes) {
    Write-Host "Reranker stopped."
} else {
    Write-Host "Reranker is not running."
}
