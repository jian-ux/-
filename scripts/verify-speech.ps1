param(
    [string]$BaseUrl = "http://localhost:8082",
    [string]$EnvFile = (Join-Path $PSScriptRoot "..\.env"),
    [string]$AudioPath = "",
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Read-DotEnv([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $values
    }
    foreach ($line in Get-Content -LiteralPath $Path) {
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

$settings = Read-DotEnv (Resolve-Path -LiteralPath $EnvFile)
$username = Get-Setting $settings "ADMIN_USERNAME"
$password = Get-Setting $settings "ADMIN_PASSWORD"
if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
    throw "ADMIN_USERNAME and ADMIN_PASSWORD must be set in the environment or .env file."
}

$loginBody = @{ username = $username; password = $password } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$BaseUrl/api/admin/login" -Method Post `
    -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 20
if ($login.code -ne 200 -or [string]::IsNullOrWhiteSpace($login.data.token)) {
    throw "Admin login failed: $($login.msg)"
}

$token = $login.data.token
$headers = @{ Authorization = "Bearer $token" }
$status = Invoke-RestMethod -Uri "$BaseUrl/api/admin/playground/speech/status" `
    -Headers $headers -TimeoutSec 20
if ($status.code -ne 200 -or -not $status.data.available) {
    throw "Speech transcription is unavailable: $($status.data.error)"
}

$generatedAudio = $false
if ([string]::IsNullOrWhiteSpace($AudioPath)) {
    $AudioPath = Join-Path ([System.IO.Path]::GetTempPath()) "feisheng-speech-acceptance.wav"
    Add-Type -AssemblyName System.Speech
    $synth = [System.Speech.Synthesis.SpeechSynthesizer]::new()
    try {
        $chineseVoice = $synth.GetInstalledVoices() |
            Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -like "zh-*" } |
            Select-Object -First 1
        if ($null -ne $chineseVoice) {
            $synth.SelectVoice($chineseVoice.VoiceInfo.Name)
            $characters = @(
                0x98DE, 0x5347, 0x667A, 0x80FD, 0x5BA2, 0x670D,
                0x8BED, 0x97F3, 0x8F6C, 0x5199, 0x9A8C, 0x6536,
                0x3002, 0x8BF7, 0x95EE, 0x5982, 0x4F55, 0x91CD,
                0x7F6E, 0x767B, 0x5F55, 0x5BC6, 0x7801, 0xFF1F
            )
            $phrase = -join ($characters | ForEach-Object { [char]$_ })
        } else {
            $phrase = "Feisheng speech transcription acceptance. How can I reset my login password?"
        }
        $synth.SetOutputToWaveFile($AudioPath)
        $synth.Speak($phrase)
    } finally {
        $synth.Dispose()
    }
    $generatedAudio = $true
}

try {
    $resolvedAudio = (Resolve-Path -LiteralPath $AudioPath).Path
    $curlArguments = @(
        "--silent", "--show-error", "--max-time", "$TimeoutSeconds",
        "-X", "POST", "$BaseUrl/api/admin/playground/speech",
        "-H", "Authorization: Bearer $token",
        "-F", "file=@$resolvedAudio"
    )
    $responseText = (& curl.exe @curlArguments) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) {
        throw "Speech upload failed with curl exit code $LASTEXITCODE."
    }
    $response = $responseText | ConvertFrom-Json
    if ($response.code -ne 200 -or [string]::IsNullOrWhiteSpace($response.data.text)) {
        throw "Speech transcription failed: $($response.msg)"
    }

    [PSCustomObject]@{
        success = $true
        provider = $response.data.provider
        model = $response.data.model
        language = $response.data.language
        audioBytes = $response.data.audioBytes
        durationMs = $response.data.durationMs
        text = $response.data.text
    } | ConvertTo-Json -Depth 3
} finally {
    if ($generatedAudio) {
        Remove-Item -LiteralPath $AudioPath -Force -ErrorAction SilentlyContinue
    }
}
