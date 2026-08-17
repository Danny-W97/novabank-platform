[CmdletBinding(SupportsShouldProcess = $true)]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

$processes = @(Get-CimInstance Win32_Process | Where-Object {
    if ([string]::IsNullOrWhiteSpace($_.CommandLine)) {
        return $false
    }

    $commandLine = $_.CommandLine
    $isProjectProcess = $commandLine.Contains($repoRoot)
    $isBankApp = $commandLine.Contains("org.demo.bankdemo.BankDemoApplication")
    $isShopApp = $commandLine.Contains("org.demo.shopapp.ShopAppApplication")
    $isSpringBootRun = $commandLine.Contains("spring-boot:run")

    return $isProjectProcess -and (($isBankApp -or $isShopApp) -or $isSpringBootRun)
})

if ($processes.Count -eq 0) {
    Write-Host "Nie znaleziono uruchomionych procesow dev banku ani sklepu."
    exit 0
}

foreach ($process in $processes) {
    $description = "PID $($process.ProcessId)"
    if ($PSCmdlet.ShouldProcess($description, "Zatrzymaj proces")) {
        Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Host "Zatrzymano $description"
    }
}

Write-Host "Aplikacje dev zostaly zatrzymane. PostgreSQL i Redis pozostaja uruchomione."
