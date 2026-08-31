param(
    [string]$BaseUrl = "http://localhost:8082",
    [int]$Samples = 20,
    [string]$ChannelType = "benchmark",
    [string]$ChannelUserId = "benchmark-user"
)

$ErrorActionPreference = "Stop"
$latencies = [System.Collections.Generic.List[double]]::new()
$retrieval = [System.Collections.Generic.List[double]]::new()
$model = [System.Collections.Generic.List[double]]::new()
$cacheHits = 0
$fallbacks = 0
$outbox = [System.Collections.Generic.List[double]]::new()

for ($i = 1; $i -le [Math]::Max(1, $Samples); $i++) {
    $body = @{ channelType = $ChannelType; channelUserId = $ChannelUserId; content = "点签电子合同第 $i 次性能基准请求"; title = "性能基准" } | ConvertTo-Json
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/core/conversation/send" -ContentType "application/json" -Body $body
    $watch.Stop()
    $result = if ($response.data) { $response.data } else { $response }
    $latencies.Add([double]$watch.ElapsedMilliseconds)
    if ($result.stageLatencies) {
        if ($null -ne $result.stageLatencies.retrievalMs) { $retrieval.Add([double]$result.stageLatencies.retrievalMs) }
        if ($null -ne $result.stageLatencies.modelMs) { $model.Add([double]$result.stageLatencies.modelMs) }
    }
    $diagnostics = $result.retrieval.decisionDiagnostics
    if ($diagnostics -and ($diagnostics.cacheHit -eq $true -or $diagnostics.cache -eq "hit")) { $cacheHits++ }
    if ($diagnostics -and ($diagnostics.fallback -eq $true -or $diagnostics.fallbackUsed -eq $true)) { $fallbacks++ }
    if ($result.customerContextDiagnostics.outboxLatencyMs) { $outbox.Add([double]$result.customerContextDiagnostics.outboxLatencyMs) }
}

function Get-Percentile([System.Collections.Generic.List[double]]$Values, [double]$Percentile) {
    if ($Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    return [Math]::Round([double]$sorted[[Math]::Max(0, [Math]::Min($sorted.Count - 1, $index))], 2)
}

[pscustomobject]@{
    samples = $latencies.Count
    totalMs = @{ p50 = Get-Percentile $latencies 50; p95 = Get-Percentile $latencies 95; p99 = Get-Percentile $latencies 99 }
    retrievalMs = @{ p50 = Get-Percentile $retrieval 50; p95 = Get-Percentile $retrieval 95; p99 = Get-Percentile $retrieval 99 }
    modelMs = @{ p50 = Get-Percentile $model 50; p95 = Get-Percentile $model 95; p99 = Get-Percentile $model 99 }
    cacheHitRate = [Math]::Round($cacheHits / [Math]::Max(1, $latencies.Count), 4)
    fallbackRate = [Math]::Round($fallbacks / [Math]::Max(1, $latencies.Count), 4)
    outboxLatencyMs = @{ p50 = Get-Percentile $outbox 50; p95 = Get-Percentile $outbox 95; p99 = Get-Percentile $outbox 99 }
} | ConvertTo-Json -Depth 5
