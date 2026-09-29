[CmdletBinding()]
param([ValidateRange(1024, 65526)][int]$BasePort = 18080, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$bundleRoot = $PSScriptRoot
$javaExecutable = (Get-Command java -ErrorAction Stop).Source
$javaProbe = [System.Diagnostics.Process]::new()
$javaProbe.StartInfo.FileName = $javaExecutable
$javaProbe.StartInfo.Arguments = '-version'
$javaProbe.StartInfo.UseShellExecute = $false
$javaProbe.StartInfo.CreateNoWindow = $true
$javaProbe.StartInfo.RedirectStandardOutput = $true
$javaProbe.StartInfo.RedirectStandardError = $true
try {
    $null = $javaProbe.Start()
    $javaInfo = $javaProbe.StandardError.ReadToEnd() + $javaProbe.StandardOutput.ReadToEnd()
    $javaProbe.WaitForExit()
    if ($javaProbe.ExitCode -ne 0) { throw 'Could not read the Java version.' }
} finally { $javaProbe.Dispose() }
if ($javaInfo -notmatch 'version\s+"(\d+)' -or [int]$Matches[1] -lt 21) {
    throw 'JDK 21 or later is required. Set JAVA_HOME and add its bin directory to PATH.'
}
$orderPort = $BasePort + 2
$inventoryPort = $BasePort + 4
$databasePort = $BasePort + 6
$catalogPort = $BasePort + 8
$catalogDatabasePort = $BasePort + 9
if (-not (Test-Path -LiteralPath (Join-Path $bundleRoot 'config/services.yml'))) { throw 'Missing config/services.yml. Extract the entire archive.' }
foreach ($port in @($BasePort, $orderPort, $inventoryPort, $databasePort, $catalogPort, $catalogDatabasePort)) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
    try { $listener.Start() }
    catch { throw "Port $port is occupied. Stop the existing instance or choose another -BasePort." }
    finally { $listener.Stop() }
}
$services = @(
    @{Name='inventory'; Jar='inventory-service.jar'; Arguments=@("--server.port=$inventoryPort")},
    @{Name='order'; Jar='order-service.jar'; Arguments=@("--server.port=$orderPort", "--sample.inventory.base-url=http://127.0.0.1:$inventoryPort")},
    @{Name='database'; Jar='database-service.jar'; Arguments=@("--server.port=$databasePort")},
    @{Name='catalog'; Jar='catalog-service.jar'; Arguments=@("--server.port=$catalogPort", "--triage.sdk.downstream-base-url=http://127.0.0.1:$inventoryPort")},
    @{Name='catalog-database'; Jar='catalog-service.jar'; Arguments=@("--server.port=$catalogDatabasePort", '--spring.profiles.active=database')},
    @{Name='agent'; Jar='agent-triage.jar'; Arguments=@("--server.port=$BasePort", '--triage.mode=DEMO', '--spring.config.additional-location=file:./config/services.yml', "--demo.order-port=$orderPort", "--demo.database-port=$databasePort", "--demo.catalog-port=$catalogPort", "--demo.catalog-database-port=$catalogDatabasePort")}
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
    $deadline = (Get-Date).AddSeconds(90)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        foreach ($child in $children) {
            if ($child.HasExited) { throw "A service stopped during startup. See logs/ (exit $($child.ExitCode))." }
        }
        try {
            $null = Invoke-RestMethod "http://127.0.0.1:$inventoryPort/lab/scenario" -TimeoutSec 2
            $null = Invoke-RestMethod "http://127.0.0.1:$orderPort/lab/scenario" -TimeoutSec 2
            $config = Invoke-RestMethod "http://127.0.0.1:$BasePort/api/config" -TimeoutSec 2
            $allReady = $config.observationAvailable
            foreach ($id in @('account-service', 'catalog-service', 'catalog-db-service')) {
                $selected = Invoke-RestMethod "http://127.0.0.1:$BasePort/api/config?service=$id" -TimeoutSec 3
                if (-not $selected.observationAvailable) { $allReady = $false }
            }
            if ($allReady) { $ready = $true; break }
        } catch {}
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Startup timed out. See logs/.' }
    Write-Host "Open http://127.0.0.1:$BasePort"
    Write-Host 'First launch uses LIVE observations and fixed rules. Model settings persist in data/; enabled models may incur fees.'
    Write-Host 'Four registered services are ready. Select order/database in the page, or call the catalog business endpoints.'
    Write-Host 'Press Ctrl+C to stop all six processes. History, model settings and logs remain in this folder.'
    if ($CheckOnly) { Write-Host 'Startup check passed; stopping this launch.'; return }
    while ($true) {
        foreach ($child in $children) { if ($child.HasExited) { throw 'A service stopped. See logs/.' } }
        Start-Sleep -Seconds 1
    }
} finally {
    foreach ($child in $children) {
        if (-not $child.HasExited) { $child.Kill(); $child.WaitForExit(5000) | Out-Null }
    }
}
