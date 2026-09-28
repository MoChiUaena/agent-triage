[CmdletBinding()]
param([ValidateRange(1024, 65531)][int]$BasePort = 18080)
$ErrorActionPreference = 'Stop'
$bundleRoot = $PSScriptRoot
$javaExecutable = (Get-Command java -ErrorAction Stop).Source
$javaInfo = (& $javaExecutable -version 2>&1 | Out-String)
if ($javaInfo -notmatch 'version\s+"(\d+)' -or [int]$Matches[1] -lt 21) {
    throw 'JDK 21 or later is required. Set JAVA_HOME and add its bin directory to PATH.'
}
$orderPort = $BasePort + 2
$inventoryPort = $BasePort + 4
foreach ($port in @($BasePort, $orderPort, $inventoryPort)) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
    try { $listener.Start() }
    catch { throw "Port $port is occupied. Stop the existing instance or choose another -BasePort." }
    finally { $listener.Stop() }
}
$services = @(
    @{Name='inventory'; Jar='inventory-service.jar'; Arguments=@("--server.port=$inventoryPort")},
    @{Name='order'; Jar='order-service.jar'; Arguments=@("--server.port=$orderPort", "--sample.inventory.base-url=http://127.0.0.1:$inventoryPort")},
    @{Name='agent'; Jar='agent-triage.jar'; Arguments=@("--server.port=$BasePort", '--triage.mode=DEMO', '--triage.observation.source=LIVE', "--triage.observation.base-url=http://127.0.0.1:$orderPort")}
)
foreach ($service in $services) {
    $service.Path = Join-Path $bundleRoot ('lib/' + $service.Jar)
    if (-not (Test-Path -LiteralPath $service.Path -PathType Leaf)) { throw "Missing $($service.Jar). Extract the entire archive first." }
}
New-Item -ItemType Directory -Path (Join-Path $bundleRoot 'logs') -Force | Out-Null
$children = @()
try {
    foreach ($service in $services) {
        $arguments = @('-jar', ('"' + $service.Path + '"')) + $service.Arguments
        $children += Start-Process -FilePath $javaExecutable -ArgumentList $arguments -WorkingDirectory $bundleRoot -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $bundleRoot "logs/$($service.Name).out.log") `
            -RedirectStandardError (Join-Path $bundleRoot "logs/$($service.Name).err.log") -PassThru
    }
    $deadline = (Get-Date).AddSeconds(60)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        foreach ($child in $children) {
            if ($child.HasExited) { throw "A service stopped during startup. See logs/ (exit $($child.ExitCode))." }
        }
        try {
            $null = Invoke-RestMethod "http://127.0.0.1:$inventoryPort/lab/scenario" -TimeoutSec 2
            $null = Invoke-RestMethod "http://127.0.0.1:$orderPort/lab/scenario" -TimeoutSec 2
            $config = Invoke-RestMethod "http://127.0.0.1:$BasePort/api/config" -TimeoutSec 2
            if ($config.observationAvailable) { $ready = $true; break }
        } catch {}
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Startup timed out. See logs/.' }
    Write-Host "Open http://127.0.0.1:$BasePort"
    Write-Host 'First launch uses LIVE observations and fixed rules. Model settings persist in data/; enabled models may incur fees.'
    Write-Host 'Press Ctrl+C to stop the three services. History, model settings and logs remain in this folder.'
    while ($true) {
        foreach ($child in $children) { if ($child.HasExited) { throw 'A service stopped. See logs/.' } }
        Start-Sleep -Seconds 1
    }
} finally {
    foreach ($child in $children) {
        if (-not $child.HasExited) { $child.Kill(); $child.WaitForExit(5000) | Out-Null }
    }
}
