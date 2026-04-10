# joularjx-simulation.ps1
#
# Demonstrates how to use JoularJX (method-level energy monitoring) together
# with the greener-spring-boot Maven plugin and Joular Core (process-level).
#
# JoularJX is an optional Java agent that provides **per-method** energy
# granularity on top of Joular Core.  When attached to the Spring Boot JVM,
# it writes CSV files to joularjx-result/ while Joular Core still provides
# the process-level energy data used by the greener plugin reports.
#
# What this script does:
#   1. Build greener-spring-boot plugins + Spring Petclinic.
#   2. Download JoularJX agent JAR (if not cached).
#   3. Generate a config.properties for JoularJX.
#   4. Run the greener:measure goal with JoularJX attached as a -javaagent.
#   5. Display JoularJX per-method energy results alongside the greener report.
#
# The greener plugin still produces its own process-level HTML/console report.
# JoularJX adds method-level CSV files under joularjx-result/ so you can
# identify which Spring Boot methods consume the most energy.
#
# Prerequisites:
#   - Java 17+
#   - Maven
#   - git -- must be on PATH
#   - oha -- installed automatically by the workload script if missing
#
# Usage:
#   .\examples\joularjx-simulation.ps1
#
# Environment variables (all optional -- sensible defaults are used):
#   PETCLINIC_VERSION      Branch/tag to clone             (default: main)
#   JOULAR_CORE_VERSION    Joular Core release tag         (default: 0.0.1-beta-1)
#   JOULARJX_VERSION       JoularJX release tag            (default: 3.1.0)
#   MEASURE_SECONDS        Measurement duration            (default: 60)
#   WARMUP_SECONDS         Warmup duration                 (default: 30)
#   THRESHOLD              Regression threshold in %       (default: 10)
#   TDP_WATTS              TDP for CPU estimation          (default: 100)
#   VM_POWER_FILE          VM power file path              (default: unset)
#   WORK_DIR               Temporary working directory     (default: $env:TEMP\greener-joularjx-sim)
#   FILTER_METHODS         JoularJX method filter          (default: org.springframework.samples.petclinic)
#   APP_PORT               HTTP port for Spring Boot        (default: random free port)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# PowerShell 5.x defaults to TLS 1.0/1.1; GitHub requires TLS 1.2+
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# -- Configuration -------------------------------------------------------------
$PetclinicVersion   = if ($env:PETCLINIC_VERSION)   { $env:PETCLINIC_VERSION }   else { "main" }
$JoularCoreVersion  = if ($env:JOULAR_CORE_VERSION) { $env:JOULAR_CORE_VERSION } else { "0.0.1-beta-1" }
$JoularJxVersion    = if ($env:JOULARJX_VERSION)    { $env:JOULARJX_VERSION }    else { "3.1.0" }
$MeasureSeconds     = if ($env:MEASURE_SECONDS)     { $env:MEASURE_SECONDS }     else { "60" }
$WarmupSeconds      = if ($env:WARMUP_SECONDS)      { $env:WARMUP_SECONDS }      else { "30" }
$Threshold          = if ($env:THRESHOLD)            { $env:THRESHOLD }            else { "10" }
$TdpWatts           = if ($env:TDP_WATTS)            { $env:TDP_WATTS }            else { "100" }
$WorkDir            = if ($env:WORK_DIR)             { $env:WORK_DIR }             else { Join-Path $env:TEMP "greener-joularjx-sim" }
$FilterMethods      = if ($env:FILTER_METHODS)       { $env:FILTER_METHODS }       else { "org.springframework.samples.petclinic" }
$AppPort            = if ($env:APP_PORT)              { $env:APP_PORT }              else {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start(); $p = $listener.LocalEndpoint.Port; $listener.Stop(); $p
}

$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptDir "..")).Path
$CommitSha   = try { git -C $ProjectRoot rev-parse HEAD 2>$null } catch { "unknown" }
$Branch      = try { git -C $ProjectRoot rev-parse --abbrev-ref HEAD 2>$null } catch { "unknown" }
if (-not $CommitSha) { $CommitSha = "unknown" }
if (-not $Branch)    { $Branch = "unknown" }

$PetclinicDir      = Join-Path $WorkDir "spring-petclinic"
$BaselineFile      = Join-Path $WorkDir "energy-baseline.json"
$RunTimestamp       = Get-Date -Format "yyyyMMdd-HHmmss"
$ReportsDir        = Join-Path $WorkDir "greener-reports-joularjx-$RunTimestamp"
$CiPowerFile       = Join-Path $WorkDir "ci-power.txt"
$JoularCacheDir    = Join-Path (Join-Path (Join-Path $HOME ".greener") "cache") "joularcore"
$JoularJxCacheDir  = Join-Path (Join-Path (Join-Path $HOME ".greener") "cache") "joularjx"

$CiEstimatorJob = $null

# -- Helpers -------------------------------------------------------------------
function Info($msg)   { Write-Output "[ii] $msg" }
function Ok($msg)     { Write-Output "[OK] $msg" }
function Warn($msg)   { Write-Output "[!!] $msg" }
function Banner($msg) {
    Write-Output ""
    Write-Output ("=" * 60)
    Write-Output "  $msg"
    Write-Output ("=" * 60)
}

function Invoke-Cmd {
    param([string]$Description, [string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

# -- Cleanup on exit ----------------------------------------------------------
$cleanupBlock = {
    if ($script:CiEstimatorJob) {
        Stop-Job $script:CiEstimatorJob -ErrorAction SilentlyContinue
        Remove-Job $script:CiEstimatorJob -Force -ErrorAction SilentlyContinue
        Info "Stopped CI power estimator job"
    }
}

try {

# -- Preflight checks ---------------------------------------------------------
Banner "Preflight checks"

foreach ($tool in @("java", "mvn", "git")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool not found on PATH. Please install it first."
    }
}

Write-Output (& { $ErrorActionPreference = 'SilentlyContinue'; java -version 2>&1 } | ForEach-Object ToString | Select-Object -First 1)
Write-Output (& { $ErrorActionPreference = 'SilentlyContinue'; mvn --version 2>&1 } | ForEach-Object ToString | Select-Object -First 1)

if (-not (Test-Path $WorkDir)) {
    New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null
}

Ok "All preflight checks passed"

# -- Build greener-spring-boot plugins -----------------------------------------
Banner "Building greener-spring-boot plugins"

Push-Location $ProjectRoot
try {
    Invoke-Cmd "Maven build" mvn @("--batch-mode", "--no-transfer-progress", "clean", "install", "-DskipTests")
} finally { Pop-Location }

# -- Clone & build Spring Petclinic --------------------------------------------
Banner "Cloning Spring Petclinic ($PetclinicVersion)"

if (Test-Path $PetclinicDir) {
    Info "Removing existing Petclinic clone"
    Remove-Item -Recurse -Force $PetclinicDir
}

Invoke-Cmd "Git clone" git @("clone", "--depth", "1", "--branch", $PetclinicVersion,
    "https://github.com/spring-projects/spring-petclinic.git", $PetclinicDir)

Banner "Building Spring Petclinic"
Push-Location $PetclinicDir
try {
    Invoke-Cmd "Petclinic build" mvn @("--batch-mode", "--no-transfer-progress", "package", "-DskipTests")
} finally { Pop-Location }

# -- Copy workload scripts ----------------------------------------------------
$ExamplesTarget = Join-Path $PetclinicDir "examples"
if (Test-Path $ExamplesTarget) { Remove-Item -Recurse -Force $ExamplesTarget }
Copy-Item -Recurse -Force (Join-Path $ProjectRoot "examples") $ExamplesTarget

# -- Power source detection ---------------------------------------------------
Banner "Detecting power source"

$PowerSource = "none"

# Check for Hubblo's RAPL driver (ScaphandreDrv) -- enables direct RAPL reading
$RaplDriverRunning = $false
try {
    $svc = sc.exe query ScaphandreDrv 2>$null
    if ($svc -and ($svc | Select-String "RUNNING")) {
        $RaplDriverRunning = $true
    }
} catch {}

if ($RaplDriverRunning) {
    $PowerSource = "rapl"
    Ok "Hubblo RAPL driver (ScaphandreDrv) detected -- using direct RAPL readings."
} elseif ($env:VM_POWER_FILE -and (Test-Path $env:VM_POWER_FILE)) {
    $PowerSource = "vm-file"
    Ok "VM power file found at $($env:VM_POWER_FILE)."
} elseif (Get-CimInstance -ClassName Win32_Processor -ErrorAction SilentlyContinue) {
    $PowerSource = "ci-estimated"
    Info "Using CPU load x TDP software estimation via Win32_Processor."
} else {
    Warn "No power source -- measurement will be skipped."
}

if ($PowerSource -eq "none") {
    Write-Output ""
    Write-Output "No usable power source detected. Exiting."
    exit 0
}

# -- Start CI CPU power estimator (if needed) ----------------------------------
if ($PowerSource -eq "ci-estimated") {
    Info "Starting CI CPU power estimator (TDP=$TdpWatts W)..."
    $EstimatorScript = Join-Path (Join-Path (Join-Path $ProjectRoot "examples") "vm-setup") "ci-cpu-energy-estimator.ps1"
    $CiEstimatorJob = Start-Job -ScriptBlock {
        param($Script, $OutFile, $Tdp)
        & $Script -OutputFile $OutFile -TdpWatts $Tdp
    } -ArgumentList $EstimatorScript, $CiPowerFile, $TdpWatts
    $env:VM_POWER_FILE = $CiPowerFile
    Start-Sleep -Seconds 2
    if (Test-Path $CiPowerFile) {
        Info "Estimator running -- current estimate: $(Get-Content $CiPowerFile) W"
    }
}

# -- Joular Core ---------------------------------------------------------------
Banner "Preparing Joular Core $JoularCoreVersion"

$JoularCoreBinary = Join-Path $JoularCacheDir "joularcore-windows-x86_64.exe"

if (Test-Path $JoularCoreBinary) {
    Ok "Joular Core found in cache: $JoularCoreBinary"
} else {
    if (-not (Test-Path $JoularCacheDir)) {
        New-Item -ItemType Directory -Path $JoularCacheDir -Force | Out-Null
    }

    $AssetName = "joularcore-windows-x86_64.exe"
    $DownloadUrl = "https://github.com/joular/joularcore/releases/download/$JoularCoreVersion/$AssetName"
    Info "Downloading Joular Core from $DownloadUrl ..."
    $Downloaded = $false
    try {
        Invoke-WebRequest -Uri $DownloadUrl -OutFile $JoularCoreBinary -UseBasicParsing
        Ok "Joular Core downloaded and cached."
        $Downloaded = $true
    } catch {
        Info "Download failed ($($_.Exception.Message)) -- will try building from source."
        if (Test-Path $JoularCoreBinary) { Remove-Item $JoularCoreBinary }
    }

    if (-not $Downloaded) {
        $HasMinGW = (Get-Command dlltool -ErrorAction SilentlyContinue) -and
                    (Get-Command gcc -ErrorAction SilentlyContinue)
        if (-not $HasMinGW) {
            if (Get-Command choco -ErrorAction SilentlyContinue) {
                Info "MinGW-w64 not found -- installing via Chocolatey..."
                & choco install mingw -y --no-progress | Out-Null
                $env:PATH = [System.Environment]::GetEnvironmentVariable("PATH", "Machine") + ";" +
                            [System.Environment]::GetEnvironmentVariable("PATH", "User")
                $HasMinGW = (Get-Command dlltool -ErrorAction SilentlyContinue) -and
                            (Get-Command gcc -ErrorAction SilentlyContinue)
            }
        }
        if (-not $HasMinGW) {
            throw ("Cannot obtain Joular Core v$JoularCoreVersion.`n" +
                   "  - No prebuilt binary available for this version on GitHub Releases.`n" +
                   "  - Building from source requires MinGW-w64 (dlltool.exe, gcc.exe).`n`n" +
                   "Options:`n" +
                   "  1. Install Chocolatey (https://chocolatey.org/install) then re-run`n" +
                   "  2. Install MinGW-w64 manually:  choco install mingw  (then re-run)`n" +
                   "  3. Manually place the binary at: $JoularCoreBinary")
        }

        if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
            Info "cargo not found -- installing Rust toolchain via rustup..."
            $RustupInit = Join-Path $WorkDir "rustup-init.exe"
            Invoke-WebRequest -Uri "https://win.rustup.rs/x86_64" -OutFile $RustupInit -UseBasicParsing
            & $RustupInit -y --default-host x86_64-pc-windows-gnu --default-toolchain stable
            $cargoBin = Join-Path $env:USERPROFILE ".cargo" | Join-Path -ChildPath "bin"
            $env:PATH = "$cargoBin;$env:PATH"
            Ok "Rust toolchain installed: $(cargo --version)"
        } else {
            & { $ErrorActionPreference = 'SilentlyContinue'; rustup set default-host x86_64-pc-windows-gnu 2>&1 } | Out-Null
        }
        & { $ErrorActionPreference = 'SilentlyContinue'; rustup target add x86_64-pc-windows-gnu 2>&1 } | Out-Null

        $JoularSrc = Join-Path $WorkDir "joularcore-src"
        if (Test-Path $JoularSrc) { Remove-Item -Recurse -Force $JoularSrc }
        Invoke-Cmd "Clone Joular Core" git @("clone", "--depth", "1", "--branch", $JoularCoreVersion,
            "https://github.com/joular/joularcore.git", $JoularSrc)

        Push-Location $JoularSrc
        try {
            Invoke-Cmd "Build Joular Core" cargo @("build", "--release", "--target", "x86_64-pc-windows-gnu")
        } finally { Pop-Location }

        Copy-Item (Join-Path (Join-Path (Join-Path (Join-Path $JoularSrc "target") "x86_64-pc-windows-gnu") "release") "joularcore.exe") $JoularCoreBinary
        Ok "Joular Core built and cached."
    }
}

# -- JoularJX agent ------------------------------------------------------------
Banner "Preparing JoularJX $JoularJxVersion"

$JoularJxJar = Join-Path $JoularJxCacheDir "joularjx-$JoularJxVersion.jar"

if (Test-Path $JoularJxJar) {
    Ok "JoularJX agent found in cache: $JoularJxJar"
} else {
    if (-not (Test-Path $JoularJxCacheDir)) {
        New-Item -ItemType Directory -Path $JoularJxCacheDir -Force | Out-Null
    }

    $JoularJxUrl = "https://github.com/joular/joularjx/releases/download/$JoularJxVersion/joularjx-$JoularJxVersion.jar"
    Info "Downloading JoularJX from $JoularJxUrl ..."
    try {
        Invoke-WebRequest -Uri $JoularJxUrl -OutFile $JoularJxJar -UseBasicParsing
        Ok "JoularJX agent downloaded and cached."
    } catch {
        throw ("Failed to download JoularJX $JoularJxVersion.`n" +
               "Download it manually from: https://github.com/joular/joularjx/releases`n" +
               "Place it at: $JoularJxJar")
    }
}

# -- Generate JoularJX config.properties ---------------------------------------
# Note: joular-core-parameters is intentionally omitted here.
# The greener Maven/Gradle plugin auto-detects the best power component
# (cpu or gpu) at startup via JoularCoreProbe.ensureJoularCoreParameters().
Banner "Generating JoularJX config.properties"

$JoularJxConfig = Join-Path $WorkDir "joularjx-config.properties"
$ConfigTimestamp = Get-Date -Format "yyyy-MM-ddTHH:mm:ss"

$ConfigContent = @"
# JoularJX configuration for greener-spring-boot simulation
# Generated by joularjx-simulation.ps1 on $ConfigTimestamp

# Filter methods to monitor -- only methods starting with this prefix
# will appear in the filtered 'app/' results.  Unfiltered results are
# always available under 'all/'.
filter-method-names=$FilterMethods

# Spring Boot runs as an application server
application-server=true

# Use Joular Core for power readings (same binary the greener plugin uses)
joular-core=true
joular-core-path=$($JoularCoreBinary -replace '\\', '/')
joular-core-ring-buffer=false

# Save per-second runtime method power data
save-runtime-data=true
overwrite-runtime-data=false

# Track method power evolution over time
track-consumption-evolution=true

# Enable call-tree energy tracking
enable-call-trees-consumption=true
save-call-trees-runtime-data=false

# Hide JoularJX agent threads from results
hide-agent-consumption=true

# Logging
logger-level=INFO
"@

[System.IO.File]::WriteAllText($JoularJxConfig, $ConfigContent)
Ok "Config written to: $JoularJxConfig"
$ConfigContent -split "`n" | Where-Object { $_ -notmatch "^#" -and $_ -ne "" } | ForEach-Object { "  $_" }

# -- VM flags ------------------------------------------------------------------
$VmFlags = @()
if ($PowerSource -eq "vm-file" -or $PowerSource -eq "ci-estimated") {
    $VmFlags = @("-Dgreener.vmMode=true", "-Dgreener.vmPowerFilePath=$($env:VM_POWER_FILE)")
}

# -- Run measurement with JoularJX --------------------------------------------
Banner "MEASUREMENT with JoularJX agent"

Info "JoularJX agent : $JoularJxJar"
Info "JoularJX config: $JoularJxConfig"
Info "Filter methods : $FilterMethods"

$OhaScript = Join-Path (Join-Path (Join-Path (Join-Path $PetclinicDir "examples") "workloads") "oha") "run.sh"

Push-Location $PetclinicDir
try {
    # The JoularJX agent is attached via the greener plugin's joularJxAgentPath
    # and joularJxConfigPath parameters. The plugin passes -javaagent: and
    # -Djoularjx.config= to the Spring Boot JVM, which causes JoularJX to run
    # inside the same JVM and write its own per-method CSV results to
    # joularjx-result/.

    $MvnArgs = @(
        "--batch-mode", "--no-transfer-progress",
        "com.patbaumgartner:greener-spring-boot-maven-plugin:0.2.0-SNAPSHOT:measure",
        "-Dgreener.joularCoreBinaryPath=$JoularCoreBinary",
        "-Dgreener.baseUrl=http://localhost:$AppPort",
        "-Dgreener.externalTrainingScriptFile=$OhaScript",
        "-Dgreener.warmupDurationSeconds=$WarmupSeconds",
        "-Dgreener.measureDurationSeconds=$MeasureSeconds",
        "-Dgreener.requestsPerSecond=20",
        "-Dgreener.healthCheckPath=/actuator/health/readiness",
        "-Dgreener.baselineFile=$BaselineFile",
        "-Dgreener.threshold=$Threshold",
        "-Dgreener.failOnRegression=false",
        "-Dgreener.reportOutputDir=$ReportsDir",
        "-Dgreener.autoUpdateBaseline=true",
        "-Dgreener.commitSha=$CommitSha",
        "-Dgreener.branch=$Branch",
        "-Dgreener.joularJxAgentPath=$JoularJxJar",
        "-Dgreener.joularJxConfigPath=$JoularJxConfig"
    ) + $VmFlags

    Invoke-Cmd "JoularJX measurement" mvn $MvnArgs
} finally { Pop-Location }

# -- Display greener plugin results --------------------------------------------
Banner "GREENER PLUGIN RESULTS (process-level)"

if (Test-Path $BaselineFile) {
    $baseline = Get-Content $BaselineFile -Raw | ConvertFrom-Json
    $bEnergy  = $baseline.report.totalEnergyJoules
    $bDuration = $baseline.report.durationSeconds
    $bAvgPower = if ($bDuration -gt 0) { $bEnergy / $bDuration } else { 0 }

    Write-Output ("  Total energy : {0:F2} J" -f $bEnergy)
    Write-Output ("  Duration     : $bDuration s")
    Write-Output ("  Avg power    : {0:F2} W" -f $bAvgPower)
    Write-Output "  Commit       : $CommitSha"
} else {
    Warn "Baseline file not found -- check Maven output above for errors."
}

Write-Output ""

# -- Display JoularJX results -------------------------------------------------
Banner "JOULARJX RESULTS (method-level)"

# JoularJX writes results relative to the JVM working directory (plugin work dir)
$JoularJxResults = Join-Path (Join-Path (Join-Path $ReportsDir "oha") "work") "joularjx-result"

if (Test-Path $JoularJxResults) {
    $LatestRun = Get-ChildItem -Directory $JoularJxResults | Sort-Object LastWriteTime -Descending | Select-Object -First 1

    if ($LatestRun) {
        Ok "JoularJX results found: $($LatestRun.FullName)"

        # Show filtered app results (methods matching FILTER_METHODS)
        $AppMethods = Join-Path (Join-Path (Join-Path $LatestRun.FullName "app") "total") "methods"
        if (Test-Path $AppMethods) {
            Write-Output ""
            Write-Output "  Filtered methods ($FilterMethods):"
            Write-Output "  ------------------------------------------"
            Get-ChildItem -File "$AppMethods\*.csv" | ForEach-Object {
                Write-Output ""
                Write-Output "  File: $($_.Name)"
                $lines = Get-Content $_.FullName | Where-Object { $_ -ne "" } | ForEach-Object {
                    $parts = $_ -split ","
                    [PSCustomObject]@{ Method = $parts[0]; Energy = [double]$parts[1] }
                } | Sort-Object -Property Energy -Descending | Select-Object -First 15
                foreach ($line in $lines) {
                    Write-Output ("    {0,-70} {1,10:F4} J" -f $line.Method, $line.Energy)
                }
            }
        }

        # Show all methods total
        $AllMethods = Join-Path (Join-Path (Join-Path $LatestRun.FullName "all") "total") "methods"
        if (Test-Path $AllMethods) {
            Write-Output ""
            Write-Output "  All methods (unfiltered, top 20):"
            Write-Output "  ------------------------------------------"
            Get-ChildItem -File "$AllMethods\*.csv" | ForEach-Object {
                $lines = Get-Content $_.FullName | Where-Object { $_ -ne "" } | ForEach-Object {
                    $parts = $_ -split ","
                    [PSCustomObject]@{ Method = $parts[0]; Energy = [double]$parts[1] }
                } | Sort-Object -Property Energy -Descending | Select-Object -First 20
                foreach ($line in $lines) {
                    Write-Output ("    {0,-70} {1,10:F4} J" -f $line.Method, $line.Energy)
                }
            }
        }

        # Copy JoularJX results alongside greener reports
        $JoularJxReportCopy = Join-Path $ReportsDir "joularjx-result"
        Copy-Item -Recurse -Force $LatestRun.FullName $JoularJxReportCopy
        Ok "JoularJX results copied to: $JoularJxReportCopy"
    } else {
        Warn "No JoularJX run directory found."
    }
} else {
    Warn "JoularJX results directory not found at: $JoularJxResults"
    Warn "JoularJX may not have started correctly. Check app-stderr.log for agent errors."
}

# -- Copy latest reports -------------------------------------------------------
$LatestReports = Join-Path $WorkDir "greener-reports-joularjx-latest"
if (Test-Path $LatestReports) { Remove-Item -Recurse -Force $LatestReports }
Copy-Item -Recurse -Force $ReportsDir $LatestReports
Info "Latest reports copied to: $LatestReports"

# -- Summary -------------------------------------------------------------------
Banner "SUMMARY"

Write-Output "  This simulation demonstrated JoularJX method-level energy monitoring"
Write-Output "  running alongside the greener-spring-boot Maven plugin."
Write-Output ""
Write-Output "  The greener report shows total process energy consumption."
Write-Output "  The JoularJX CSVs show which individual methods consumed the most energy."
Write-Output "  Together, they provide both a high-level overview and method-level detail."
Write-Output ""
Write-Output "Reports saved to:"
Write-Output "  Greener report : $ReportsDir"
Write-Output "  JoularJX CSVs  : $ReportsDir\joularjx-result\"
Write-Output "  Baseline JSON  : $BaselineFile"
Write-Output ""
Write-Output "Open in browser:"
Write-Output "  file:///$($ReportsDir -replace '\\', '/')/oha/greener-energy-report.html"

} finally {
    # Run cleanup
    & $cleanupBlock
}
