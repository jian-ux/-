param(
    [switch]$Background,
    [switch]$ForceInstall,
    [int]$StartupTimeoutSeconds = 1200
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$serviceRoot = Join-Path $repoRoot "services\qwen3-vl-reranker"
$runtimeRoot = Join-Path $repoRoot ".runtime\qwen3-vl-reranker"
$venvRoot = Join-Path $runtimeRoot "venv"
$python = Join-Path $venvRoot "Scripts\python.exe"
$cacheRoot = Join-Path $repoRoot ".model-cache\huggingface"
$logRoot = Join-Path $runtimeRoot "logs"
$pidFile = Join-Path $runtimeRoot "reranker.pid"
$requirements = Join-Path $serviceRoot "requirements.txt"
$installStamp = Join-Path $runtimeRoot "requirements.sha256"

function Get-DotEnvValue([string]$Name) {
    $envFile = Join-Path $repoRoot ".env"
    if (-not (Test-Path $envFile)) { return $null }
    $line = Get-Content $envFile | Where-Object { $_ -match "^$([regex]::Escape($Name))=" } |
        Select-Object -Last 1
    if ($null -eq $line) { return $null }
    return ($line -split "=", 2)[1].Trim()
}

New-Item -ItemType Directory -Force -Path $runtimeRoot, $cacheRoot, $logRoot | Out-Null

if (-not (Test-Path $python)) {
    Write-Host "Creating reranker Python environment..."
    python -m venv $venvRoot
}

$requirementsHash = (Get-FileHash $requirements -Algorithm SHA256).Hash
$scriptHash = (Get-FileHash $PSCommandPath -Algorithm SHA256).Hash
$requiredHash = "$requirementsHash-$scriptHash"
$installedHash = if (Test-Path $installStamp) { (Get-Content $installStamp -Raw).Trim() } else { "" }
if ($ForceInstall -or $requiredHash -ne $installedHash) {
    $env:PIP_CACHE_DIR = Join-Path $repoRoot ".model-cache\pip"
    Write-Host "Installing CUDA PyTorch and reranker dependencies..."
    & $python -m pip install --upgrade pip
    if ($LASTEXITCODE -ne 0) { throw "pip upgrade failed" }
    & $python -m pip install --index-url https://download.pytorch.org/whl/cu128 `
        torch==2.10.0 torchvision==0.25.0
    if ($LASTEXITCODE -ne 0) { throw "CUDA PyTorch installation failed" }
    & $python -m pip install -r $requirements
    if ($LASTEXITCODE -ne 0) { throw "Dependency installation failed" }
    Set-Content -Path $installStamp -Value $requiredHash -Encoding ascii
}

$apiKey = Get-DotEnvValue "RERANK_API_KEY"
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    throw "RERANK_API_KEY is missing from .env"
}

$env:HF_HOME = $cacheRoot
$env:SENTENCE_TRANSFORMERS_HOME = $cacheRoot
$env:HF_HUB_DISABLE_SYMLINKS_WARNING = "1"
$env:TORCH_HOME = Join-Path $repoRoot ".model-cache\torch"
$env:RERANK_API_KEY = $apiKey
$env:RERANK_MODEL = (Get-DotEnvValue "RERANK_MODEL")
if ([string]::IsNullOrWhiteSpace($env:RERANK_MODEL)) {
    $env:RERANK_MODEL = "Qwen/Qwen3-VL-Reranker-2B"
}
$env:RERANK_DEVICE = "cuda"
$env:RERANK_DTYPE = "bfloat16"
$env:RERANK_MAX_CANDIDATES = "10"
$env:RERANK_MAX_LENGTH = "2048"
$env:RERANK_BATCH_SIZE = "1"

$uvicornArgs = @(
    "-m", "uvicorn", "app:app",
    "--app-dir", $serviceRoot,
    "--host", "0.0.0.0",
    "--port", "8091",
    "--workers", "1"
)

if (-not $Background) {
    & $python @uvicornArgs
    exit $LASTEXITCODE
}

if (Test-Path $pidFile) {
    $existingPid = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    $existingProcess = if ($existingPid) {
        Get-CimInstance Win32_Process -Filter "ProcessId=$existingPid" -ErrorAction SilentlyContinue
    } else { $null }
    if ($existingProcess -and $existingProcess.CommandLine -like '*-m uvicorn app:app*' -and
            $existingProcess.CommandLine -like '*qwen3-vl-reranker*') {
        Write-Host "Reranker is already running (PID $existingPid)."
        exit 0
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

$stdout = Join-Path $logRoot "stdout.log"
$stderr = Join-Path $logRoot "stderr.log"
$launcher = Start-Process -FilePath $python -ArgumentList $uvicornArgs `
    -WorkingDirectory $serviceRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr
Start-Sleep -Seconds 2
$process = Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
    Where-Object { $_.CommandLine -like '*-m uvicorn app:app*' -and
        $_.CommandLine -like '*qwen3-vl-reranker*' } |
    Sort-Object CreationDate -Descending |
    Select-Object -First 1
if ($null -eq $process) {
    if ($launcher.HasExited) { throw "Reranker exited before the server process started. See $stderr" }
    $process = [pscustomobject]@{ ProcessId = $launcher.Id }
}
Set-Content -Path $pidFile -Value $process.ProcessId -Encoding ascii

$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
Write-Host "Reranker started as PID $($process.ProcessId); waiting for model readiness..."
while ((Get-Date) -lt $deadline) {
    if (-not (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue)) {
        throw "Reranker exited during startup. See $stderr"
    }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:8091/health" -TimeoutSec 3
        if ($health.status -eq "ok") {
            Write-Host "Reranker is ready: $($health.model) on $($health.gpu)"
            exit 0
        }
    } catch {
        # Model download/loading keeps the port unavailable until startup completes.
    }
    Start-Sleep -Seconds 5
}

throw "Reranker did not become ready within $StartupTimeoutSeconds seconds. See $stderr"
