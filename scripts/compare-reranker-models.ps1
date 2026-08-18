param(
    [string]$BaseUrl = "http://localhost:8082",
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\.env"),
    [string]$InputPath = (Join-Path $PSScriptRoot "..\docs\examples\dianqian-knowledge-governance-dialog-evaluation-20260817.json"),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\docs\evaluation-results"),
    [string[]]$Models = @(
        "Qwen/Qwen3-VL-Reranker-2B",
        "Qwen/Qwen3-Reranker-0.6B"
    ),
    [double[]]$ScoreTemperatures = @(1.0, 8.0),
    [int]$TimeoutSeconds = 1200
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$startScript = Join-Path $PSScriptRoot "start-reranker.ps1"
$stopScript = Join-Path $PSScriptRoot "stop-reranker.ps1"

function Read-DotEnv([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        if ($line -match '^\s*([^#][^=]*)=(.*)$') {
            $values[$matches[1].Trim()] = $matches[2]
        }
    }
    return $values
}

function Get-Setting([hashtable]$Values, [string]$Name) {
    $processValue = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($processValue)) { return $processValue }
    if ($Values.ContainsKey($Name)) { return $Values[$Name] }
    return ""
}

function Write-JsonFile([string]$Path, [object]$Value) {
    $json = $Value | ConvertTo-Json -Depth 40
    [System.IO.File]::WriteAllText(
        $Path, $json, [System.Text.UTF8Encoding]::new($false))
}

function Get-ContainerRerankModel {
    $lines = & docker inspect feisheng-bot --format '{{range .Config.Env}}{{println .}}{{end}}'
    if ($LASTEXITCODE -ne 0) { return "" }
    $line = $lines | Where-Object { $_ -match '^RAG_RERANK_MODEL=' } | Select-Object -Last 1
    if ($null -eq $line) { return "" }
    return ($line -split '=', 2)[1].Trim()
}

function Wait-AppHealthy([int]$Timeout) {
    $deadline = (Get-Date).AddSeconds($Timeout)
    do {
        $status = & docker inspect feisheng-bot --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>$null
        if ($LASTEXITCODE -eq 0 -and $status -eq "healthy") { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Application did not become healthy within $Timeout seconds"
}

function Switch-RerankerModel([string]$Model, [double]$ScoreTemperature) {
    Write-Host "Switching to $Model (temperature=$ScoreTemperature) ..."
    $timer = [Diagnostics.Stopwatch]::StartNew()
    & $stopScript | Out-Host
    & $startScript -Background -Model $Model -BatchSize 1 -CacheMaxEntries 0 `
        -ScoreTemperature $ScoreTemperature `
        -StartupTimeoutSeconds $TimeoutSeconds | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Failed to start reranker model $Model" }

    $previousOverride = [Environment]::GetEnvironmentVariable("RAG_RERANK_MODEL", "Process")
    try {
        $env:RAG_RERANK_MODEL = $Model
        & docker compose up -d --no-deps --force-recreate app | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Failed to recreate application for $Model" }
    } finally {
        if ($null -eq $previousOverride) {
            [Environment]::SetEnvironmentVariable("RAG_RERANK_MODEL", $null, "Process")
        } else {
            $env:RAG_RERANK_MODEL = $previousOverride
        }
    }
    Wait-AppHealthy ([Math]::Min($TimeoutSeconds, 180))

    $health = Invoke-RestMethod -Uri "http://127.0.0.1:8091/health" -TimeoutSec 10
    if ($health.status -ne "ok" -or $health.model -ne $Model) {
        throw "Reranker health check returned model '$($health.model)' while '$Model' was expected"
    }
    if ([math]::Abs([double]$health.score_temperature - $ScoreTemperature) -gt 0.000001) {
        throw "Reranker health check returned temperature '$($health.score_temperature)' while '$ScoreTemperature' was expected"
    }
    $containerModel = Get-ContainerRerankModel
    if ($containerModel -ne $Model) {
        throw "Application container uses '$containerModel' while '$Model' was expected"
    }
    $timer.Stop()
    return [math]::Round($timer.Elapsed.TotalMilliseconds, 3)
}

function Invoke-RagEvaluation([string]$Model, [double]$ScoreTemperature,
                              [double]$StartupMs,
                              [hashtable]$Settings, [object]$SourceRequest) {
    $username = Get-Setting $Settings "ADMIN_USERNAME"
    $password = Get-Setting $Settings "ADMIN_PASSWORD"
    if ([string]::IsNullOrWhiteSpace($username) -or
            [string]::IsNullOrWhiteSpace($password)) {
        throw "ADMIN_USERNAME and ADMIN_PASSWORD must be configured"
    }

    $loginBody = @{ username = $username; password = $password } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$BaseUrl/api/admin/login" -Method Post `
        -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 20
    if ($login.code -ne 200 -or [string]::IsNullOrWhiteSpace($login.data.token)) {
        throw "Admin login failed: $($login.msg)"
    }

    $request = [pscustomobject]@{
        name = "$($SourceRequest.name)-$($Model -replace '[^A-Za-z0-9._-]', '-')-t$ScoreTemperature"
        cases = $SourceRequest.cases
    }
    $body = $request | ConvertTo-Json -Depth 30
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-RestMethod -Uri "$BaseUrl/api/admin/rag/evaluate" `
        -Method Post -Headers @{ Authorization = "Bearer $($login.data.token)" } `
        -ContentType "application/json; charset=utf-8" -Body $body `
        -TimeoutSec $TimeoutSeconds
    $timer.Stop()
    if ($response.code -ne 200) { throw "Evaluation failed: $($response.msg)" }

    $gpuMemory = (& nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits |
        Select-Object -First 1).Trim()
    return [pscustomobject]@{
        model = $Model
        scoreTemperature = $ScoreTemperature
        startupMs = $StartupMs
        evaluationMs = [math]::Round($timer.Elapsed.TotalMilliseconds, 3)
        gpuMemoryUsedMiB = [int]$gpuMemory
        report = $response.data
    }
}

function Get-ExactSourceMetrics([object]$Report) {
    $cases = @($Report.cases | Where-Object { $null -ne $_.expectedSourceId })
    $citationHits = @($cases | Where-Object { $_.citationMatched -eq $true }).Count
    $topOne = @($cases | Where-Object { $_.sourceRank -eq 1 }).Count
    $reciprocalRank = 0.0
    foreach ($case in $cases) {
        if ($null -ne $case.sourceRank -and [int]$case.sourceRank -gt 0) {
            $reciprocalRank += 1.0 / [int]$case.sourceRank
        }
    }
    return [pscustomobject]@{
        expected = $cases.Count
        citationHits = $citationHits
        citationHitRate = if ($cases.Count -eq 0) { 0 } else {
            [math]::Round($citationHits / $cases.Count, 4)
        }
        topOne = $topOne
        topOneRate = if ($cases.Count -eq 0) { 0 } else {
            [math]::Round($topOne / $cases.Count, 4)
        }
        meanReciprocalRank = if ($cases.Count -eq 0) { 0 } else {
            [math]::Round($reciprocalRank / $cases.Count, 4)
        }
    }
}

function Get-ModelSummary([object]$Result) {
    $report = $Result.report
    return [pscustomobject]@{
        model = $Result.model
        scoreTemperature = $Result.scoreTemperature
        startupMs = $Result.startupMs
        evaluationMs = $Result.evaluationMs
        gpuMemoryUsedMiB = $Result.gpuMemoryUsedMiB
        total = $report.total
        decisionAccuracy = $report.decisionAccuracy
        answerRecall = $report.answerRecall
        noAnswerRecall = $report.noAnswerRecall
        citationHitRate = $report.citationHitRate
        sourceHitAtOneRate = $report.sourceHitAtOneRate
        meanReciprocalRank = $report.meanReciprocalRank
        exactSource = Get-ExactSourceMetrics $report
    }
}

function Compare-Cases([object]$Left, [object]$Right) {
    $leftCases = @{}
    foreach ($case in $Left.report.cases) { $leftCases[$case.id] = $case }
    $differences = @()
    foreach ($rightCase in $Right.report.cases) {
        if (-not $leftCases.ContainsKey($rightCase.id)) { continue }
        $leftCase = $leftCases[$rightCase.id]
        $sourceChanged = [string]$leftCase.sourceRank -ne [string]$rightCase.sourceRank
        $decisionChanged = $leftCase.decisionCorrect -ne $rightCase.decisionCorrect -or
            $leftCase.actualAnswerable -ne $rightCase.actualAnswerable
        if ($sourceChanged -or $decisionChanged) {
            $differences += [pscustomobject]@{
                id = $rightCase.id
                question = $rightCase.question
                expectedSourceId = $rightCase.expectedSourceId
                leftDecisionCorrect = $leftCase.decisionCorrect
                rightDecisionCorrect = $rightCase.decisionCorrect
                leftAnswerable = $leftCase.actualAnswerable
                rightAnswerable = $rightCase.actualAnswerable
                leftSourceRank = $leftCase.sourceRank
                rightSourceRank = $rightCase.sourceRank
                leftConfidence = $leftCase.confidence
                rightConfidence = $rightCase.confidence
            }
        }
    }
    return $differences
}

$resolvedEnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
$resolvedInputPath = (Resolve-Path -LiteralPath $InputPath).Path
$settings = Read-DotEnv $resolvedEnvFile
$finalModel = Get-Setting $settings "RERANK_MODEL"
if ([string]::IsNullOrWhiteSpace($finalModel)) {
    throw "RERANK_MODEL must be configured in $resolvedEnvFile"
}
if ($Models.Count -lt 2) { throw "At least two models are required" }
if ($Models.Count -ne $ScoreTemperatures.Count) {
    throw "Models and ScoreTemperatures must contain the same number of values"
}
$finalTemperatureValue = Get-Setting $settings "RERANK_SCORE_TEMPERATURE"
$finalTemperature = if ([string]::IsNullOrWhiteSpace($finalTemperatureValue)) {
    1.0
} else {
    [double]::Parse($finalTemperatureValue, [Globalization.CultureInfo]::InvariantCulture)
}
$sourceRequest = Get-Content -LiteralPath $resolvedInputPath -Raw -Encoding utf8 |
    ConvertFrom-Json
$validCases = @($sourceRequest.cases | Where-Object {
    $_.PSObject.Properties.Name -contains "answerable" -and
        $null -ne $_.answerable -and -not [string]::IsNullOrWhiteSpace($_.question)
})
$excludedCases = @($sourceRequest.cases | Where-Object {
    $_.PSObject.Properties.Name -notcontains "answerable" -or
        $null -eq $_.answerable -or [string]::IsNullOrWhiteSpace($_.question)
})
$sourceRequest = [pscustomobject]@{
    name = $sourceRequest.name
    cases = $validCases
}
if ($excludedCases.Count -gt 0) {
    Write-Host "Excluded $($excludedCases.Count) non-RAG cases: $($excludedCases.id -join ', ')"
}

$results = @()
$failure = $null
try {
    for ($index = 0; $index -lt $Models.Count; $index++) {
        $model = $Models[$index]
        $temperature = $ScoreTemperatures[$index]
        $startupMs = Switch-RerankerModel $model $temperature
        $result = Invoke-RagEvaluation $model $temperature $startupMs $settings $sourceRequest
        $results += $result
        Write-Host ("Completed {0} (temperature={1}): decision={2:P2}, exactTop1={3:P2}, evalMs={4}" -f `
            $model, $temperature, [double]$result.report.decisionAccuracy,
            [double](Get-ExactSourceMetrics $result.report).topOneRate,
            $result.evaluationMs)
    }
} catch {
    $failure = $_
}

try {
    $loadedModel = ""
    $loadedTemperature = [double]::NaN
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:8091/health" -TimeoutSec 5
        $loadedModel = $health.model
        $loadedTemperature = [double]$health.score_temperature
    } catch {}
    if ($loadedModel -ne $finalModel -or
            [math]::Abs($loadedTemperature - $finalTemperature) -gt 0.000001 -or
            (Get-ContainerRerankModel) -ne $finalModel) {
        $null = Switch-RerankerModel $finalModel $finalTemperature
    }
} catch {
    if ($null -eq $failure) { $failure = $_ } else {
        Write-Warning "Failed to restore final model: $($_.Exception.Message)"
    }
}
if ($null -ne $failure) { throw $failure }

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutputDirectory) | Out-Null
$resultFiles = @()
foreach ($result in $results) {
    $slug = $result.model -replace '[^A-Za-z0-9._-]', '-'
    $temperatureSlug = ([string]$result.scoreTemperature) -replace '\.', '_'
    $path = Join-Path $resolvedOutputDirectory `
        "reranker-evaluation-$slug-t$temperatureSlug-$timestamp.json"
    Write-JsonFile $path $result
    $resultFiles += (Resolve-Path $path).Path
}

$summaries = @($results | ForEach-Object { Get-ModelSummary $_ })
$comparison = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    dataset = $resolvedInputPath
    evaluatedCases = $validCases.Count
    excludedCaseIds = @($excludedCases | ForEach-Object { $_.id })
    finalModel = $finalModel
    finalScoreTemperature = $finalTemperature
    resultFiles = $resultFiles
    models = $summaries
    changedCases = @(Compare-Cases $results[0] $results[1])
}
$comparisonPath = Join-Path $resolvedOutputDirectory `
    "reranker-model-comparison-$timestamp.json"
Write-JsonFile $comparisonPath $comparison

$summaries | Select-Object model,scoreTemperature,decisionAccuracy,answerRecall,noAnswerRecall,
    sourceHitAtOneRate,meanReciprocalRank,
    @{Name='exactTop1';Expression={$_.exactSource.topOneRate}},
    @{Name='exactMRR';Expression={$_.exactSource.meanReciprocalRank}},
    evaluationMs,gpuMemoryUsedMiB | Format-Table -AutoSize
Write-Host "Comparison: $((Resolve-Path $comparisonPath).Path)"
