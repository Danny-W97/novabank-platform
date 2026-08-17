[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$envDirectory = Join-Path $repoRoot ".novabank\env"
$envFile = Join-Path $envDirectory "dev.env"
New-Item -ItemType Directory -Path $envDirectory -Force | Out-Null

function New-ApiSecret {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $bytes = New-Object byte[] 32
        $rng.GetBytes($bytes)
        return -join ($bytes | ForEach-Object { $_.ToString("x2") })
    }
    finally {
        $rng.Dispose()
    }
}

function New-EncryptionSecret {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $bytes = New-Object byte[] 32
        $rng.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes)
    }
    finally {
        $rng.Dispose()
    }
}

function Get-ExistingSecret {
    if (-not (Test-Path -LiteralPath $envFile)) {
        return $null
    }

    $line = Get-Content -LiteralPath $envFile -Encoding UTF8 |
        Where-Object { $_ -match '^BANK_API_KEY=(.+)$' } |
        Select-Object -First 1
    if ($line -and $line -match '^BANK_API_KEY=(.+)$') {
        return $Matches[1].Trim()
    }
    return $null
}

function Get-ExistingValue([string]$name) {
    if (-not (Test-Path -LiteralPath $envFile)) {
        return $null
    }

    $line = Get-Content -LiteralPath $envFile -Encoding UTF8 |
        Where-Object { $_ -match ('^' + [regex]::Escape($name) + '=(.*)$') } |
        Select-Object -First 1
    if ($line -and $line -match ('^' + [regex]::Escape($name) + '=(.*)$')) {
        return $Matches[1].Trim()
    }
    return $null
}

$secret = if (-not $Force) { Get-ExistingSecret } else { $null }
if ([string]::IsNullOrWhiteSpace($secret) -or $secret -eq "replace-with-a-long-random-secret") {
    $secret = New-ApiSecret
    $action = "wygenerowano nowy"
}
else {
    $action = "uzyto istniejacego"
}

$totpEncryptionKey = Get-ExistingValue "TOTP_ENCRYPTION_KEY"
if ([string]::IsNullOrWhiteSpace($totpEncryptionKey) -or $totpEncryptionKey -eq "generate-a-32-byte-base64-key") {
    $totpEncryptionKey = New-EncryptionSecret
    $totpAction = "wygenerowano nowy"
}
else {
    $totpAction = "uzyto istniejacego"
}

$lines = @(
    "SPRING_PROFILES_ACTIVE=dev",
    "BANK_API_KEY=$secret",
    "BANK_API_KEY_ID=legacy",
    "BANK_API_KEYS=",
    "TOTP_ENCRYPTION_KEY=$totpEncryptionKey"
)
[System.IO.File]::WriteAllLines(
    $envFile,
    $lines,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host "Dev env: $action klucz API."
Write-Host "Dev env: $totpAction klucz szyfrowania TOTP."
Write-Host "Plik zapisano w .novabank/env/dev.env."
Write-Host "Nie uzywaj tego pliku w produkcji."
