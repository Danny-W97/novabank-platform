[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$wslProjectRoot = (& wsl.exe wslpath -u -a $projectRoot).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslProjectRoot)) {
    throw "Nie udalo sie przeliczyc sciezki projektu na format WSL."
}

$escapedProjectRoot = $wslProjectRoot.Replace('"', '\"')
$wslArguments = "--cd `"$escapedProjectRoot`" bash ./scripts/backup/backup.sh internal-worker"
Start-Process -FilePath "wsl.exe" -ArgumentList $wslArguments

Write-Host "Otwarto oddzielne okno WSL dla kopii zapasowej."
