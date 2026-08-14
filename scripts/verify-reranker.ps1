param(
    [string]$BaseUrl = "http://127.0.0.1:8091"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"
$apiKeyLine = Get-Content $envFile | Where-Object { $_ -match '^RERANK_API_KEY=' } |
    Select-Object -Last 1
if ($null -eq $apiKeyLine) { throw "RERANK_API_KEY is missing from .env" }
$apiKey = ($apiKeyLine -split '=', 2)[1].Trim()

$health = Invoke-RestMethod -Uri "$BaseUrl/health" -TimeoutSec 10
if ($health.status -ne "ok") { throw "Reranker is not ready" }

$body = @{
    model = "Qwen/Qwen3-VL-Reranker-2B"
    query = "How do I sign an electronic contract online with Dianqian?"
    documents = @(
        "The weather is sunny today with a high temperature of thirty degrees.",
        "Dianqian supports online electronic contract creation and signing, contract management, and seal authorization."
    )
    top_n = 2
} | ConvertTo-Json -Depth 4

$started = Get-Date
$response = Invoke-RestMethod -Uri "$BaseUrl/rerank" -Method Post `
    -Headers @{ Authorization = "Bearer $apiKey" } `
    -ContentType "application/json; charset=utf-8" -Body $body -TimeoutSec 60
$elapsedMs = [math]::Round(((Get-Date) - $started).TotalMilliseconds)

if ($response.results.Count -ne 2) { throw "Expected two rerank results" }
$relevant = $response.results | Where-Object { $_.index -eq 1 }
$irrelevant = $response.results | Where-Object { $_.index -eq 0 }
if ($null -eq $relevant -or $null -eq $irrelevant) { throw "Response indices are incomplete" }
if ([double]$relevant.relevance_score -le [double]$irrelevant.relevance_score) {
    throw "Relevant document did not receive the higher score"
}

[pscustomobject]@{
    Status = "ok"
    Model = $health.model
    GPU = $health.gpu
    LatencyMs = $elapsedMs
    RelevantScore = [math]::Round([double]$relevant.relevance_score, 6)
    IrrelevantScore = [math]::Round([double]$irrelevant.relevance_score, 6)
}
