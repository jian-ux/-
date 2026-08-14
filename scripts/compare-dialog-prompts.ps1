param(
    [string]$BaseUrl = "http://localhost:8082",
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\.env"),
    [string]$InputPath = (Join-Path $PSScriptRoot "..\docs\examples\dialog-evaluation.json"),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\docs\evaluation-results"),
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Read-DotEnv([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $values
    }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding utf8) {
        if ($line -match '^\s*([^#][^=]*)=(.*)$') {
            $values[$matches[1].Trim()] = $matches[2]
        }
    }
    return $values
}

function Get-Setting([hashtable]$Values, [string]$Name) {
    $processValue = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        return $processValue
    }
    if ($Values.ContainsKey($Name)) {
        return $Values[$Name]
    }
    return ""
}

function Write-JsonFile([string]$Path, [object]$Value) {
    $json = $Value | ConvertTo-Json -Depth 30
    [System.IO.File]::WriteAllText(
        $Path, $json, [System.Text.UTF8Encoding]::new($false))
}

function Metric([string]$Name, [object]$V1, [object]$V2, [string]$Direction) {
    $v1Number = [double]$V1
    $v2Number = [double]$V2
    $comparison = if ($v1Number -eq $v2Number) {
        "tie"
    } elseif (($Direction -eq "higher" -and $v2Number -gt $v1Number) -or
              ($Direction -eq "lower" -and $v2Number -lt $v1Number)) {
        "v2"
    } else {
        "v1"
    }
    [PSCustomObject]@{
        name = $Name
        direction = $Direction
        v1 = $v1Number
        v2 = $v2Number
        winner = $comparison
    }
}

$settings = Read-DotEnv (Resolve-Path -LiteralPath $EnvFile)
$username = Get-Setting $settings "ADMIN_USERNAME"
$password = Get-Setting $settings "ADMIN_PASSWORD"
if ([string]::IsNullOrWhiteSpace($username) -or
        [string]::IsNullOrWhiteSpace($password)) {
    throw "ADMIN_USERNAME and ADMIN_PASSWORD must be set in the environment or .env file."
}

$loginBody = @{ username = $username; password = $password } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$BaseUrl/api/admin/login" -Method Post `
    -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 20
if ($login.code -ne 200 -or [string]::IsNullOrWhiteSpace($login.data.token)) {
    throw "Admin login failed: $($login.msg)"
}

$headers = @{ Authorization = "Bearer $($login.data.token)" }
$sourceRequest = Get-Content -LiteralPath (Resolve-Path -LiteralPath $InputPath) `
    -Raw -Encoding utf8 | ConvertFrom-Json
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutputDirectory) | Out-Null
$reports = @{}

foreach ($version in @("v1", "v2")) {
    $request = $sourceRequest | ConvertTo-Json -Depth 30 | ConvertFrom-Json
    $request.name = "$($sourceRequest.name)-$version"
    $request | Add-Member -NotePropertyName promptVersion -NotePropertyValue $version -Force
    $body = $request | ConvertTo-Json -Depth 30
    $response = Invoke-RestMethod -Uri "$BaseUrl/api/admin/rag/evaluate-dialog" `
        -Method Post -Headers $headers -ContentType "application/json; charset=utf-8" `
        -Body $body -TimeoutSec $TimeoutSeconds
    if ($response.code -ne 200) {
        throw "$version evaluation failed: $($response.msg)"
    }
    if ($response.data.promptVersion -ne $version) {
        throw "$version evaluation returned promptVersion '$($response.data.promptVersion)'"
    }
    $reports[$version] = $response.data
    Write-JsonFile (Join-Path $resolvedOutputDirectory "dialog-prompt-$version-$timestamp.json") $response
}

$v1 = $reports.v1
$v2 = $reports.v2
$metrics = @(
    Metric "decisionAccuracy" $v1.decisionAccuracy $v2.decisionAccuracy "higher"
    Metric "groundingAccuracy" $v1.groundingAccuracy $v2.groundingAccuracy "higher"
    Metric "requiredPhraseHitRate" $v1.requiredPhraseHitRate $v2.requiredPhraseHitRate "higher"
    Metric "forbiddenPhraseViolations" $v1.forbiddenPhraseViolations $v2.forbiddenPhraseViolations "lower"
    Metric "handoffAccuracy" $v1.handoffAccuracy $v2.handoffAccuracy "higher"
    Metric "piiLeakCount" $v1.piiLeakCount $v2.piiLeakCount "lower"
    Metric "modelErrorCount" $v1.modelErrorCount $v2.modelErrorCount "lower"
)

$v1Cases = @{}
foreach ($case in $v1.cases) { $v1Cases[$case.id] = $case }
$caseDifferences = foreach ($v2Case in $v2.cases) {
    $v1Case = $v1Cases[$v2Case.id]
    if ($null -ne $v1Case -and $v1Case.reply -ne $v2Case.reply) {
        [PSCustomObject]@{
            id = $v2Case.id
            question = $v2Case.question
            sourceV1 = $v1Case.source
            sourceV2 = $v2Case.source
            v1Reply = $v1Case.reply
            v2Reply = $v2Case.reply
            v1DecisionCorrect = $v1Case.decisionCorrect
            v2DecisionCorrect = $v2Case.decisionCorrect
            v1MissingRequired = @($v1Case.missingRequiredPhrases)
            v2MissingRequired = @($v2Case.missingRequiredPhrases)
            v1ForbiddenFound = @($v1Case.forbiddenPhrasesFound)
            v2ForbiddenFound = @($v2Case.forbiddenPhrasesFound)
        }
    }
}

$v2Regression = @($metrics | Where-Object { $_.winner -eq "v1" })
$v2Improvement = @($metrics | Where-Object { $_.winner -eq "v2" })
$recommendation = if ($v2Regression.Count -gt 0) {
    "keep_v1"
} elseif ($v2Improvement.Count -gt 0) {
    "candidate_v2"
} else {
    "metrics_tied_review_changed_replies"
}

$comparison = [PSCustomObject]@{
    evaluatedAt = (Get-Date).ToString("o")
    sampleName = $sourceRequest.name
    totalCases = $v1.total
    productionDefault = "v2"
    recommendation = $recommendation
    metrics = $metrics
    changedReplyCount = @($caseDifferences).Count
    caseDifferences = @($caseDifferences)
}
$comparisonPath = Join-Path $resolvedOutputDirectory "dialog-prompt-comparison-$timestamp.json"
Write-JsonFile $comparisonPath $comparison
$comparison | ConvertTo-Json -Depth 6
Write-Host "Comparison saved to $comparisonPath"
