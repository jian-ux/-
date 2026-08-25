[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8082",
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\.env"),
    [string]$DatasetPath = (Join-Path $PSScriptRoot "..\docs\examples\dialog-decision-regression.json"),
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\docs\evaluation-results\dialog-decision-regression-latest.json"),
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

function Test-CasePassed([object]$Case) {
    return [bool]$Case.decisionCorrect `
        -and ($null -eq $Case.groundingMatched -or [bool]$Case.groundingMatched) `
        -and @($Case.missingRequiredPhrases).Count -eq 0 `
        -and @($Case.forbiddenPhrasesFound).Count -eq 0 `
        -and ($null -eq $Case.handoffCorrect -or [bool]$Case.handoffCorrect) `
        -and -not [bool]$Case.piiLeak `
        -and -not [bool]$Case.modelError
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

$requestBody = Get-Content -LiteralPath ([System.IO.Path]::GetFullPath($DatasetPath)) `
    -Raw -Encoding UTF8
$response = Invoke-RestMethod -Method Post `
    -Uri ($BaseUrl.TrimEnd("/") + "/api/admin/rag/evaluate-dialog") `
    -Headers @{ Authorization = "Bearer $($login.data.token)" } `
    -ContentType "application/json; charset=utf-8" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($requestBody)) `
    -TimeoutSec $TimeoutSeconds
if ($response.code -ne 200) {
    throw "Dialog decision regression failed: $($response.msg)"
}

$absoluteOutput = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.Directory]::CreateDirectory(
    [System.IO.Path]::GetDirectoryName($absoluteOutput)) | Out-Null
[System.IO.File]::WriteAllText(
    $absoluteOutput, ($response | ConvertTo-Json -Depth 100),
    [System.Text.UTF8Encoding]::new($false))

$report = $response.data
Write-Host ("Dialog decision regression: passed={0}, cases={1}, passedCases={2}, failedCases={3}" -f `
    $report.passed, $report.total, $report.passedCaseCount, $report.failedCaseCount)
Write-Host "Evaluation report: $absoluteOutput"

if (-not [bool]$report.passed) {
    foreach ($case in $report.cases) {
        if (-not (Test-CasePassed $case)) {
            Write-Error ("Case {0} failed: decision={1}, status={2}, source={3}" -f `
                $case.id, $case.answerDecision, $case.answerStatus, $case.source) `
                -ErrorAction Continue
        }
    }
    exit 1
}
