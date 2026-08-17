[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("setup", "start", "status", "logs", "stop")]
    [string]$Command = "status",

    [ValidateSet("dev", "prod")]
    [string]$Profile = "dev",

    [ValidateSet("", "prometheus", "grafana", "alertmanager")]
    [string]$Service = "",

    [switch]$Force
)

$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$wslProjectRoot = (& wsl.exe wslpath -u -a $projectRoot).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslProjectRoot)) {
    throw "Nie udalo sie przeliczyc sciezki projektu na format WSL."
}

$arguments = @(
    "--cd", $wslProjectRoot,
    "bash", "./scripts/monitoring/monitoring.sh",
    $Command, "--profile", $Profile
)

if ($Force) {
    $arguments += "--force"
}

if ($Command -eq "logs" -and -not [string]::IsNullOrWhiteSpace($Service)) {
    $arguments += $Service
}

& wsl.exe @arguments
exit $LASTEXITCODE
