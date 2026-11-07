[CmdletBinding()]
param(
    [switch]$SkipInfrastructureCheck
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

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

function Test-LocalPort {
    param(
        [int]$Port
    )

    try {
        return (Test-NetConnection -ComputerName "127.0.0.1" -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue)
    }
    catch {
        return $false
    }
}

function Stop-ExistingDevApps {
    $processes = Get-CimInstance Win32_Process | Where-Object {
        if ([string]::IsNullOrWhiteSpace($_.CommandLine)) {
            return $false
        }

        $commandLine = $_.CommandLine
        $isBank = $commandLine.Contains("org.demo.bankdemo.BankDemoApplication")
        $isShop = $commandLine.Contains("org.demo.shopapp.ShopAppApplication")
        $isProjectProcess = $commandLine.Contains($repoRoot)
        return $isProjectProcess -and ($isBank -or $isShop)
    }

    foreach ($process in $processes) {
        Write-Host "Zatrzymuję poprzedni proces dev: PID $($process.ProcessId)"
        Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) {
    throw "Nie znaleziono mvn.cmd. Uruchom skrypt w środowisku, w którym Maven jest dostępny w PATH."
}

if (-not $SkipInfrastructureCheck) {
    $requiredPorts = @(5300, 5400, 6379, 11026)
    $unavailablePorts = @($requiredPorts | Where-Object { -not (Test-LocalPort -Port $_) })
    if ($unavailablePorts.Length -gt 0) {
        throw "Brak dostepnej infrastruktury WSL 2 na portach: $($unavailablePorts -join ', '). Uruchom PostgreSQL, Redis i Mailpit (bank-mailpit) albo uzyj -SkipInfrastructureCheck."
    }
}

$secret = New-ApiSecret
$envFile = Join-Path $repoRoot ".novabank\env\dev.env"

# TOTP secrets are encrypted in the database, so this key must survive app restarts.
if (-not (Test-Path -LiteralPath $envFile) -or
    -not (Get-Content -LiteralPath $envFile -Encoding UTF8 | Where-Object { $_ -match '^TOTP_ENCRYPTION_KEY=([^\s]+)$' })) {
    & (Join-Path $PSScriptRoot "generate-dev-env.ps1")
}

$totpLine = Get-Content -LiteralPath $envFile -Encoding UTF8 |
    Where-Object { $_ -match '^TOTP_ENCRYPTION_KEY=([^\s]+)$' } |
    Select-Object -First 1
if (-not $totpLine -or $totpLine -notmatch '^TOTP_ENCRYPTION_KEY=(\S+)$') {
    throw "Brak TOTP_ENCRYPTION_KEY w .novabank/env/dev.env. Uruchom scripts/generate-dev-env.ps1."
}
$totpEncryptionKey = $Matches[1]

# Both applications inherit the same ephemeral key from the child PowerShell windows.
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:BANK_API_KEY = $secret
$env:BANK_API_KEY_ID = "legacy"
$env:BANK_API_KEYS = ""
$env:TOTP_ENCRYPTION_KEY = $totpEncryptionKey

Stop-ExistingDevApps
Start-Sleep -Seconds 1

$bankCommand = "Set-Location -LiteralPath '$repoRoot'; mvn.cmd spring-boot:run"
$shopCommand = "Set-Location -LiteralPath '$repoRoot'; mvn.cmd -f shop-app/pom.xml spring-boot:run"

Start-Process powershell.exe -ArgumentList @(
    "-NoExit",
    "-NoProfile",
    "-Command",
    $bankCommand
) -WorkingDirectory $repoRoot | Out-Null

Start-Process powershell.exe -ArgumentList @(
    "-NoExit",
    "-NoProfile",
    "-Command",
    $shopCommand
) -WorkingDirectory $repoRoot | Out-Null

Write-Host "Uruchomiono bank i shop-app w profilu dev."
Write-Host "Klucz API jest tymczasowy; klucz szyfrowania TOTP jest stabilny i pochodzi z .novabank/env/dev.env."
Write-Host "Bank: http://localhost:8080"
Write-Host "Mailpit (skrzynka DEV): http://localhost:18026 (SMTP: 127.0.0.1:11026)"
Write-Host "Sklep: http://localhost:8081/novatech_shop.html"
