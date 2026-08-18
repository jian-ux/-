[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8082",
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\.env"),
    [Parameter(Mandatory = $true)]
    [string]$DatasetPath,
    [string]$OutputPath,
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Read-DotEnv([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
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

$settings = Read-DotEnv ([System.IO.Path]::GetFullPath($EnvFile))
$username = Get-Setting $settings "ADMIN_USERNAME"
$password = Get-Setting $settings "ADMIN_PASSWORD"
if ([string]::IsNullOrWhiteSpace($username) -or
        [string]::IsNullOrWhiteSpace($password)) {
    throw "ADMIN_USERNAME and ADMIN_PASSWORD must be set in the environment or .env file."
}

$loginBody = @{ username = $username; password = $password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri ($BaseUrl.TrimEnd("/") + "/api/admin/login") `
    -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 20
if ($login.code -ne 200 -or [string]::IsNullOrWhiteSpace($login.data.token)) {
    throw "Admin login failed: $($login.msg)"
}
$headers = @{ Authorization = "Bearer $($login.data.token)" }

$resolvedDataset = (Resolve-Path -LiteralPath $DatasetPath).Path
$requestBody = Get-Content -LiteralPath $resolvedDataset -Raw -Encoding UTF8
$dataset = $requestBody | ConvertFrom-Json
if ($null -eq $dataset.qualityGate) {
    throw "Evaluation dataset must define qualityGate: $resolvedDataset"
}

$uri = $BaseUrl.TrimEnd("/") + "/api/admin/rag/evaluate"
$response = Invoke-RestMethod -Method Post -Uri $uri -Headers $headers `
    -ContentType "application/json; charset=utf-8" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($requestBody)) `
    -TimeoutSec $TimeoutSeconds
if ($null -ne $response.code -and [int]$response.code -ne 200) {
    throw "RAG evaluation failed: $($response.msg)"
}

$report = if ($null -ne $response.data) { $response.data } else { $response }
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $absoluteOutput = [System.IO.Path]::GetFullPath($OutputPath)
    $outputDirectory = [System.IO.Path]::GetDirectoryName($absoluteOutput)
    if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
        [System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    }
    $json = $response | ConvertTo-Json -Depth 100
    [System.IO.File]::WriteAllText(
        $absoluteOutput, $json, [System.Text.UTF8Encoding]::new($false))
    Write-Host "Evaluation report: $absoluteOutput"
}

Write-Host ("Dataset={0} Pipeline={1} Accuracy={2:P2} Hit@1={3:P2} MRR={4:N4}" -f `
    $report.datasetVersion, $report.pipelineVersion, $report.decisionAccuracy, `
    $report.sourceHitAtOneRate, $report.meanReciprocalRank)

if (-not [bool]$report.releaseGatePassed) {
    Write-Error "RAG quality gate failed." -ErrorAction Continue
    foreach ($check in $report.qualityGate.checks) {
        if (-not [bool]$check.passed) {
            Write-Error ("{0}: actual={1:N4}, required {2} {3:N4}" -f `
                $check.metric, $check.actual, $check.operator, $check.required) `
                -ErrorAction Continue
        }
    }
    exit 1
}

Write-Host "RAG quality gate passed."
exit 0
