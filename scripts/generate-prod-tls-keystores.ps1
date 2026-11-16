param(
    [string]$OutputDirectory = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")) ".novabank\tls\prod"),
    [switch]$Force
)

$ErrorActionPreference = "Stop"

function Find-Keytool {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\keytool.exe"
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    $roots = @(
        "C:\Program Files\Java",
        "C:\Program Files\JetBrains",
        (Join-Path $HOME ".jdks")
    ) | Where-Object { Test-Path -LiteralPath $_ }

    $candidate = Get-ChildItem -Path $roots -Filter keytool.exe -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName

    if (-not $candidate) {
        throw "Nie znaleziono keytool.exe. Ustaw JAVA_HOME wskazujace na JDK."
    }

    return $candidate
}

function Convert-SecureStringToPlainText([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

$keytool = Find-Keytool
$output = [IO.Path]::GetFullPath($OutputDirectory)
$bankStore = Join-Path $output "bank-server.p12"
$shopStore = Join-Path $output "shop-server.p12"

New-Item -ItemType Directory -Path $output -Force | Out-Null

if ((Test-Path -LiteralPath $bankStore) -or (Test-Path -LiteralPath $shopStore)) {
    if (-not $Force) {
        throw "Keystore juz istnieje. Uzyj -Force tylko aby wygenerowac nowe certyfikaty."
    }
    Remove-Item -LiteralPath $bankStore, $shopStore -Force
}

$bankPassword = Read-Host "Haslo keystore banku" -AsSecureString
$shopPassword = Read-Host "Haslo keystore sklepu" -AsSecureString
$bankPlain = Convert-SecureStringToPlainText $bankPassword
$shopPlain = Convert-SecureStringToPlainText $shopPassword

try {
    & $keytool -genkeypair -noprompt `
        -alias "bank-local" `
        -keyalg RSA `
        -keysize 3072 `
        -validity 825 `
        -storetype PKCS12 `
        -keystore $bankStore `
        -storepass $bankPlain `
        -keypass $bankPlain `
        -dname "CN=localhost" `
        -ext "SAN=dns:localhost,ip:127.0.0.1"

    & $keytool -genkeypair -noprompt `
        -alias "shop-local" `
        -keyalg RSA `
        -keysize 3072 `
        -validity 825 `
        -storetype PKCS12 `
        -keystore $shopStore `
        -storepass $shopPlain `
        -keypass $shopPlain `
        -dname "CN=localhost" `
        -ext "SAN=dns:localhost,ip:127.0.0.1"
} finally {
    $bankPlain = $null
    $shopPlain = $null
}

Write-Host "Wygenerowano keystore banku: $bankStore"
Write-Host "Wygenerowano keystore sklepu: $shopStore"
Write-Host "Certyfikaty sa lokalne/self-signed i przeznaczone do prod-like na WSL/localhost."
