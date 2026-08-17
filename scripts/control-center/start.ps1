[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ControlCenterArguments = @()
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path

if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw "Nie znaleziono wsl.exe. Na Linux/macOS uruchom bezposrednio: bash scripts/control-center/control-center.sh"
}

$wslProjectRoot = (& wsl.exe wslpath -u -a $projectRoot).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslProjectRoot)) {
    throw "Nie udalo sie przeliczyc sciezki projektu na format WSL."
}

& wsl.exe --cd $wslProjectRoot bash ./scripts/control-center/control-center.sh @ControlCenterArguments
exit $LASTEXITCODE
