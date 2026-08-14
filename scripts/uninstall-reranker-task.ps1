param(
    [string]$TaskName = "FeishengBot-Qwen3-Reranker"
)

$ErrorActionPreference = "Stop"
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -ne $task) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}
& (Join-Path $PSScriptRoot "stop-reranker.ps1")
Write-Host "Removed scheduled task $TaskName."
