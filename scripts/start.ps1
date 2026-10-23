[CmdletBinding()]
param(
    [switch]$Cli,
    [switch]$NoBrowser,
    [ValidateRange(1024, 65535)][int]$Port = 18110,
    [string]$PythonExecutable = $env:NOVABANK_CONTROL_PYTHON,
    [switch]$Wsl
)
$ErrorActionPreference = 'Stop'
$panelProjectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ($Cli) {
    & (Join-Path $PSScriptRoot 'control-center\start.ps1')
    exit $LASTEXITCODE
}
$panelArgs = @('--port', "$Port")
if ($NoBrowser) { $panelArgs += '--no-browser' }
if (-not $Wsl -and -not $PythonExecutable) {
    foreach ($panelPythonName in @('python3.exe', 'python.exe', 'py.exe')) {
        $panelPythonCommand = Get-Command $panelPythonName -ErrorAction SilentlyContinue
        if ($panelPythonCommand -and $panelPythonCommand.Source -notlike '*\WindowsApps\*') {
            $PythonExecutable = $panelPythonCommand.Source
            break
        }
    }
}
if ($Wsl -or (-not $PythonExecutable -and (Get-Command wsl.exe -ErrorAction SilentlyContinue))) {
    $panelWslRoot = (& wsl.exe wslpath -u -a $panelProjectRoot).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $panelWslRoot) { throw 'Nie udało się odczytać ścieżki WSL.' }
    & wsl.exe --cd $panelWslRoot bash ./scripts/start.sh @panelArgs
    exit $LASTEXITCODE
}
if (-not $PythonExecutable) {
    throw 'Wymagany Python 3.10+. Użyj -PythonExecutable "ścieżka\python.exe" lub -Wsl (Python w WSL).'
}
$panelPythonArgs = @()
if ([IO.Path]::GetFileName($PythonExecutable) -eq 'py.exe') { $panelPythonArgs += '-3' }
$panelPythonArgs += @('-X', 'utf8')
& $PythonExecutable @panelPythonArgs (Join-Path $PSScriptRoot 'control-center\web\server.py') @panelArgs
exit $LASTEXITCODE
