param(
    [string]$BaseUrl = "http://127.0.0.1:8091",
    [int[]]$LogIds = @(1300, 1304, 1305, 1306, 1297),
    [int]$Runs = 3,
    [int]$WarmupRuns = 1,
    [string]$BatchLabel = "unknown",
    [string]$BaselinePath = "",
    [string]$OutputPath = "",
    [string]$MySqlContainer = "feisheng-mysql",
    [double]$ScoreTolerance = 0.001
)

$ErrorActionPreference = "Stop"
if ($Runs -lt 1) { throw "Runs must be at least 1" }
if ($WarmupRuns -lt 0) { throw "WarmupRuns cannot be negative" }
if ($LogIds.Count -lt 1 -or $LogIds | Where-Object { $_ -le 0 }) {
    throw "LogIds must contain positive integers"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"
$apiKeyLine = Get-Content $envFile | Where-Object { $_ -match '^RERANK_API_KEY=' } |
    Select-Object -Last 1
if ($null -eq $apiKeyLine) { throw "RERANK_API_KEY is missing from .env" }
$apiKey = ($apiKeyLine -split '=', 2)[1].Trim()
if ([string]::IsNullOrWhiteSpace($apiKey)) { throw "RERANK_API_KEY is blank" }

function Invoke-MySqlQuery([string]$Sql) {
    $lines = $Sql | & docker exec -i $MySqlContainer sh -c `
        'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 --batch --raw --skip-column-names -D feisheng_bot_db' 2>$null
    if ($LASTEXITCODE -ne 0) { throw "MySQL query failed" }
    return @($lines)
}

function Decode-Base64([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))
}

function Get-ConfidenceTier([double[]]$Scores) {
    $ordered = @($Scores | Sort-Object -Descending)
    $top = $ordered[0]
    $second = if ($ordered.Count -gt 1) { $ordered[1] } else { 0.0 }
    $gap = $top - $second
    if ($top -ge 0.65 -and $gap -ge 0.08) { return "HIGH" }
    if ($top -ge 0.40 -and $gap -ge -0.10) { return "MEDIUM" }
    return "LOW"
}

function Get-Percentile([double[]]$Values, [double]$Percentile) {
    $ordered = @($Values | Sort-Object)
    if ($ordered.Count -eq 0) { return 0.0 }
    $index = [math]::Ceiling($Percentile * $ordered.Count) - 1
    $index = [math]::Max(0, [math]::Min($ordered.Count - 1, $index))
    return [double]$ordered[$index]
}

$idCsv = ($LogIds | ForEach-Object { [string]$_ }) -join ','
$querySql = @'
SELECT id,
       REPLACE(REPLACE(TO_BASE64(CONVERT(JSON_UNQUOTE(
           JSON_EXTRACT(trace_json, '$.originalQuery')) USING utf8mb4)), CHAR(10), ''), CHAR(13), '')
FROM bot_ai_reply_log
WHERE id IN (__IDS__) AND JSON_VALID(trace_json)
ORDER BY FIELD(id, __IDS__);
'@.Replace('__IDS__', $idCsv)

$candidateSql = @'
SELECT l.id,
       j.ord,
       j.chunk_id,
       REPLACE(REPLACE(TO_BASE64(CONVERT(
           CASE WHEN j.structured_qa = 'true' THEN
               CONCAT_WS(CHAR(10),
                   COALESCE(NULLIF(k.qa_question, ''), d.title, ''),
                   COALESCE(NULLIF(k.qa_answer, ''), k.content, ''))
           ELSE
               CONCAT_WS(CHAR(10),
                   COALESCE(d.title, ''),
                   COALESCE(NULLIF(k.content, ''), k.qa_answer, ''))
           END USING utf8mb4)), CHAR(10), ''), CHAR(13), '')
FROM bot_ai_reply_log l
JOIN JSON_TABLE(l.trace_json, '$.candidates[*]' COLUMNS(
    ord FOR ORDINALITY,
    chunk_id BIGINT PATH '$.chunkId',
    structured_qa VARCHAR(5) PATH '$.structuredQa'
)) j
LEFT JOIN bot_knowledge_chunk k ON k.id = j.chunk_id
LEFT JOIN bot_knowledge_document d ON d.id = k.document_id
WHERE l.id IN (__IDS__)
ORDER BY FIELD(l.id, __IDS__), j.ord;
'@.Replace('__IDS__', $idCsv)

$queries = @{}
foreach ($line in (Invoke-MySqlQuery $querySql)) {
    $parts = $line -split "`t", 2
    if ($parts.Count -eq 2) { $queries[[int]$parts[0]] = Decode-Base64 $parts[1] }
}

$documents = @{}
$chunkIds = @{}
foreach ($line in (Invoke-MySqlQuery $candidateSql)) {
    $parts = $line -split "`t", 4
    if ($parts.Count -ne 4) { continue }
    $logId = [int]$parts[0]
    if (-not $documents.ContainsKey($logId)) {
        $documents[$logId] = [Collections.Generic.List[string]]::new()
        $chunkIds[$logId] = [Collections.Generic.List[long]]::new()
    }
    if ($parts[2] -eq 'NULL' -or [string]::IsNullOrWhiteSpace($parts[3])) {
        throw "Log $logId contains a candidate that cannot be reconstructed"
    }
    $chunkIds[$logId].Add([long]$parts[2])
    $documents[$logId].Add((Decode-Base64 $parts[3]))
}

$cases = @()
foreach ($logId in $LogIds) {
    if (-not $queries.ContainsKey($logId)) { throw "Log $logId has no original query" }
    if (-not $documents.ContainsKey($logId) -or $documents[$logId].Count -ne 10) {
        $count = if ($documents.ContainsKey($logId)) { $documents[$logId].Count } else { 0 }
        throw "Log $logId has $count reconstructed candidates; expected 10"
    }
    $cases += [pscustomobject]@{
        logId = $logId
        query = $queries[$logId]
        documents = @($documents[$logId])
        chunkIds = @($chunkIds[$logId])
    }
}

$health = Invoke-RestMethod -Uri "$BaseUrl/health" -TimeoutSec 10
if ($health.status -ne "ok") { throw "Reranker is not ready" }
$headers = @{ Authorization = "Bearer $apiKey" }

function Invoke-RerankCase($Case) {
    $body = @{
        model = $health.model
        query = $Case.query
        documents = $Case.documents
        top_n = $Case.documents.Count
    } | ConvertTo-Json -Depth 5 -Compress
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-RestMethod -Uri "$BaseUrl/rerank" -Method Post `
        -Headers $headers -ContentType "application/json; charset=utf-8" `
        -Body $body -TimeoutSec 120
    $timer.Stop()
    if ($response.results.Count -ne $Case.documents.Count) {
        throw "Log $($Case.logId) returned $($response.results.Count) scores; expected $($Case.documents.Count)"
    }
    $scores = [double[]]::new($Case.documents.Count)
    foreach ($item in $response.results) {
        $scores[[int]$item.index] = [double]$item.relevance_score
    }
    $rankedIndices = @($response.results | ForEach-Object { [int]$_.index })
    $orderedScores = @($scores | Sort-Object -Descending)
    return [pscustomobject]@{
        latencyMs = [math]::Round($timer.Elapsed.TotalMilliseconds, 3)
        rankedIndices = $rankedIndices
        rankedChunkIds = @($rankedIndices | ForEach-Object { $Case.chunkIds[$_] })
        scoresByIndex = @($scores | ForEach-Object { [math]::Round($_, 9) })
        topScore = [math]::Round($orderedScores[0], 9)
        secondScore = [math]::Round($orderedScores[1], 9)
        scoreGap = [math]::Round($orderedScores[0] - $orderedScores[1], 9)
        confidenceTier = Get-ConfidenceTier $scores
    }
}

for ($warmup = 0; $warmup -lt $WarmupRuns; $warmup++) {
    foreach ($case in $cases) { $null = Invoke-RerankCase $case }
}

$caseResults = @()
foreach ($case in $cases) {
    $runsForCase = @()
    for ($run = 1; $run -le $Runs; $run++) {
        $measurement = Invoke-RerankCase $case
        $measurement | Add-Member -NotePropertyName run -NotePropertyValue $run
        $runsForCase += $measurement
    }
    $latencies = @($runsForCase | ForEach-Object { [double]$_.latencyMs })
    $caseResults += [pscustomobject]@{
        logId = $case.logId
        query = $case.query
        chunkIds = $case.chunkIds
        latencyMedianMs = [math]::Round((Get-Percentile $latencies 0.5), 3)
        latencyP90Ms = [math]::Round((Get-Percentile $latencies 0.9), 3)
        runs = $runsForCase
    }
}

$comparison = $null
if (-not [string]::IsNullOrWhiteSpace($BaselinePath)) {
    $resolvedBaseline = (Resolve-Path $BaselinePath).Path
    $baseline = Get-Content $resolvedBaseline -Raw | ConvertFrom-Json
    $checks = @()
    foreach ($currentCase in $caseResults) {
        $baselineCase = $baseline.cases | Where-Object { $_.logId -eq $currentCase.logId } |
            Select-Object -First 1
        if ($null -eq $baselineCase) { throw "Baseline has no log $($currentCase.logId)" }
        $reference = $baselineCase.runs[0]
        foreach ($run in $currentCase.runs) {
            $rankEqual = (($run.rankedChunkIds -join ',') -eq ($reference.rankedChunkIds -join ','))
            $topEqual = ($run.rankedChunkIds[0] -eq $reference.rankedChunkIds[0])
            $tierEqual = ($run.confidenceTier -eq $reference.confidenceTier)
            $maxDifference = 0.0
            for ($index = 0; $index -lt $run.scoresByIndex.Count; $index++) {
                $difference = [math]::Abs(
                    [double]$run.scoresByIndex[$index] - [double]$reference.scoresByIndex[$index])
                if ($difference -gt $maxDifference) { $maxDifference = $difference }
            }
            $checks += [pscustomobject]@{
                logId = $currentCase.logId
                run = $run.run
                rankingEqual = $rankEqual
                top1Equal = $topEqual
                confidenceTierEqual = $tierEqual
                maxScoreDifference = [math]::Round($maxDifference, 9)
                scoreWithinTolerance = ($maxDifference -le $ScoreTolerance)
                passed = ($rankEqual -and $topEqual -and $tierEqual -and
                    $maxDifference -le $ScoreTolerance)
            }
        }
    }
    $comparison = [pscustomobject]@{
        baselinePath = $resolvedBaseline
        scoreTolerance = $ScoreTolerance
        passed = -not ($checks | Where-Object { -not $_.passed })
        checks = $checks
    }
}

$allLatencies = @($caseResults | ForEach-Object { $_.runs } |
    ForEach-Object { [double]$_.latencyMs })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    batchLabel = $BatchLabel
    model = $health.model
    gpu = $health.gpu
    sampleCount = $cases.Count
    runsPerSample = $Runs
    warmupRuns = $WarmupRuns
    latencyMedianMs = [math]::Round((Get-Percentile $allLatencies 0.5), 3)
    latencyP90Ms = [math]::Round((Get-Percentile $allLatencies 0.9), 3)
    comparison = $comparison
    cases = $caseResults
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputPath = Join-Path $repoRoot "tmp\reranker-benchmark-$BatchLabel-$stamp.json"
}
$outputDirectory = Split-Path -Parent $OutputPath
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
$report | ConvertTo-Json -Depth 10 | Set-Content -Path $OutputPath -Encoding utf8
$report | Select-Object batchLabel,model,gpu,sampleCount,runsPerSample,
    latencyMedianMs,latencyP90Ms,@{Name='ComparisonPassed';Expression={
        if ($null -eq $_.comparison) { $null } else { $_.comparison.passed }
    }}
Write-Host "Report: $((Resolve-Path $OutputPath).Path)"
if ($null -ne $comparison -and -not $comparison.passed) { exit 2 }
