# joularcode-simulation.ps1
#
# Demonstrates how to use Joular Code Java (method-level energy monitoring)
# together with the greener-spring-boot Maven plugin and Joular Core (process-level).
#
# Joular Code Java is the successor of JoularJX. It is an optional Java agent
# that provides **per-method** energy granularity on top of Joular Core.
# When attached to the Spring Boot JVM, it writes CSV files to
# joular-code-java-results/ while Joular Core still provides the process-level
# energy data used by the greener plugin reports.
#
# What this script does:
#   1. Build greener-spring-boot plugins + Spring Petclinic.
#   2. Download Joular Code Java agent JAR (if not cached).
#   3. Generate a joularcodejava.properties config file.
#   4. Run the greener:measure goal with Joular Code Java attached as a -javaagent.
#   5. Display Joular Code Java per-method energy results alongside the greener report.
#
# The greener plugin still produces its own process-level HTML/console report.
# Joular Code Java adds method-level CSV files under joular-code-java-results/
# so you can identify which Spring Boot methods consume the most energy.
#
# NOTE: Joular Code Java requires Java 21+.
#
# Prerequisites:
#   - Java 21+
#   - Maven
#   - git -- must be on PATH
#   - oha -- installed automatically by the workload script if missing
#
# Usage:
#   .\examples\joularcode-simulation.ps1
#
# Environment variables (all optional -- sensible defaults are used):
#   PETCLINIC_VERSION           Branch/tag to clone                  (default: main)
#   JOULAR_CORE_VERSION         Joular Core release tag              (default: 0.0.1-beta-4)
#   JOULARCODEJAVA_VERSION      Joular Code Java release tag         (default: 0.0.1-alpha-4)
#   MEASURE_SECONDS             Measurement duration                 (default: 60)
#   WARMUP_SECONDS              Warmup duration                      (default: 30)
#   THRESHOLD                   Regression threshold in %            (default: 10)
#   TDP_WATTS                   TDP for CPU estimation               (default: 100)
#   VM_POWER_FILE               VM power file path                   (default: unset)
#   WORK_DIR                    Temporary working directory          (default: $env:TEMP\greener-joularcode-sim)
#   FILTER_METHODS              Joular Code Java method filter       (default: org.springframework.samples.petclinic)
#   APP_PORT                    HTTP port for Spring Boot             (default: random free port)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# PowerShell 5.x defaults to TLS 1.0/1.1; GitHub requires TLS 1.2+
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# -- Configuration -------------------------------------------------------------
$PetclinicVersion          = if ($env:PETCLINIC_VERSION)          { $env:PETCLINIC_VERSION }          else { "main" }
$JoularCoreVersion         = if ($env:JOULAR_CORE_VERSION)        { $env:JOULAR_CORE_VERSION }        else { "0.0.1-beta-4" }
$JoularCodeJavaVersion     = if ($env:JOULARCODEJAVA_VERSION)     { $env:JOULARCODEJAVA_VERSION }     else { "0.0.1-alpha-4" }
$MeasureSeconds            = if ($env:MEASURE_SECONDS)            { $env:MEASURE_SECONDS }            else { "60" }
$WarmupSeconds             = if ($env:WARMUP_SECONDS)             { $env:WARMUP_SECONDS }             else { "30" }
$Threshold                 = if ($env:THRESHOLD)                   { $env:THRESHOLD }                   else { "10" }
$TdpWatts                  = if ($env:TDP_WATTS)                   { $env:TDP_WATTS }                   else { "100" }
$WorkDir                   = if ($env:WORK_DIR)                    { $env:WORK_DIR }                    else { Join-Path $env:TEMP "greener-joularcode-sim" }
$FilterMethods             = if ($env:FILTER_METHODS)              { $env:FILTER_METHODS }              else { "org.springframework.samples.petclinic" }
$AppPort                   = if ($env:APP_PORT)                    { $env:APP_PORT }                    else {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start(); $p = $listener.LocalEndpoint.Port; $listener.Stop(); $p
}

$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptDir "..")).Path
$CommitSha   = try { git -C $ProjectRoot rev-parse HEAD 2>$null } catch { "unknown" }
$Branch      = try { git -C $ProjectRoot rev-parse --abbrev-ref HEAD 2>$null } catch { "unknown" }
if (-not $CommitSha) { $CommitSha = "unknown" }
if (-not $Branch)    { $Branch = "unknown" }

$PetclinicDir               = Join-Path $WorkDir "spring-petclinic"
$BaselineFile               = Join-Path $WorkDir "energy-baseline.json"
$RunTimestamp               = Get-Date -Format "yyyyMMdd-HHmmss"
$ReportsDir                 = Join-Path $WorkDir "greener-reports-joularcode-$RunTimestamp"
$CiPowerFile                = Join-Path $WorkDir "ci-power.txt"
$JoularCacheDir             = Join-Path (Join-Path (Join-Path $HOME ".greener") "cache") "joularcore"
$JoularCodeJavaCacheDir     = Join-Path (Join-Path (Join-Path $HOME ".greener") "cache") "joularcodejava"

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

# Joular Code Java requires Java 21+
$javaVerOutput = (& { $ErrorActionPreference = 'SilentlyContinue'; java -version 2>&1 }) | ForEach-Object ToString | Select-Object -First 1
Write-Output $javaVerOutput
if ($javaVerOutput -match '"(1\.\d|[2-9]|1[0-9]|20)\.' -or ($javaVerOutput -notmatch '"(2[1-9]|[3-9]\d)')) {
    # Check major version more reliably
    $majorVer = & { $ErrorActionPreference = 'SilentlyContinue'; java -XshowSettings:property -version 2>&1 } |
        Where-Object { $_ -match 'java.vm.specification.version\s*=\s*(\d+)' } |
        ForEach-Object { [int]$Matches[1] } | Select-Object -First 1
    if (-not $majorVer) {
        $majorVer = [int]($javaVerOutput -replace '.*"(\d+)\..*', '$1' -replace '.*"(\d+)".*', '$1')
    }
    if ($majorVer -lt 21) {
        throw "Joular Code Java requires Java 21+. Detected version: $majorVer. Please install JDK 21+ and update JAVA_HOME."
    }
}

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

# -- Joular Code Java agent ----------------------------------------------------
Banner "Preparing Joular Code Java $JoularCodeJavaVersion"

# The release asset uses only the base semver (strips pre-release qualifier, e.g. -alpha-4)
$JoularCodeJavaBaseVersion = $JoularCodeJavaVersion -replace '-(?:alpha|beta|rc|m|ea).*$', ''
$JoularCodeJavaJar = Join-Path $JoularCodeJavaCacheDir "joularcodejava-$JoularCodeJavaBaseVersion.jar"

if (Test-Path $JoularCodeJavaJar) {
    Ok "Joular Code Java agent found in cache: $JoularCodeJavaJar"
} else {
    if (-not (Test-Path $JoularCodeJavaCacheDir)) {
        New-Item -ItemType Directory -Path $JoularCodeJavaCacheDir -Force | Out-Null
    }

    $JoularCodeJavaUrl = "https://github.com/joular/joularcode-java/releases/download/$JoularCodeJavaVersion/joularcodejava-$JoularCodeJavaBaseVersion.jar"
    Info "Downloading Joular Code Java from $JoularCodeJavaUrl ..."
    try {
        Invoke-WebRequest -Uri $JoularCodeJavaUrl -OutFile $JoularCodeJavaJar -UseBasicParsing
        Ok "Joular Code Java agent downloaded and cached."
    } catch {
        throw ("Failed to download Joular Code Java $JoularCodeJavaVersion.`n" +
               "Download it manually from: https://github.com/joular/joularcode-java/releases`n" +
               "Place it at: $JoularCodeJavaJar")
    }
}

# -- Generate joularcodejava.properties ----------------------------------------
# The greener plugin automatically enables the JoularCore ring buffer (-r flag)
# when joularCodeJavaAgentPath is set.  The default power-source-type=ringbuffer
# will then read from the ring buffer at Local\JoularCoreRing on Windows.
Banner "Generating joularcodejava.properties"

$JoularCodeJavaConfig = Join-Path $WorkDir "joularcodejava.properties"
$ConfigTimestamp = Get-Date -Format "yyyy-MM-ddTHH:mm:ss"

$ConfigContent = @"
# Joular Code Java configuration for greener-spring-boot simulation
# Generated by joularcode-simulation.ps1 on $ConfigTimestamp

# Read power from the Joular Core ring buffer (default).
# The greener plugin automatically starts Joular Core with -r when this agent is attached.
power-source-type=ringbuffer

# Filter methods to monitor -- only methods starting with this prefix
# will appear in the filtered 'methods-power-app.csv'.
# Unfiltered results are always available in 'methods-power-all.csv'.
filter-method-names=$FilterMethods
"@

[System.IO.File]::WriteAllText($JoularCodeJavaConfig, $ConfigContent)
Ok "Config written to: $JoularCodeJavaConfig"
$ConfigContent -split "`n" | Where-Object { $_ -notmatch "^#" -and $_ -ne "" } | ForEach-Object { "  $_" }

# -- VM flags ------------------------------------------------------------------
$VmFlags = @()
if ($PowerSource -eq "vm-file" -or $PowerSource -eq "ci-estimated") {
    $VmFlags = @("-Dgreener.vmMode=true", "-Dgreener.vmPowerFilePath=$($env:VM_POWER_FILE)")
}

# -- Run measurement with Joular Code Java ------------------------------------
Banner "MEASUREMENT with Joular Code Java agent"

Info "Joular Code Java agent : $JoularCodeJavaJar"
Info "Joular Code Java config: $JoularCodeJavaConfig"
Info "Filter methods         : $FilterMethods"

$OhaScript = Join-Path (Join-Path (Join-Path (Join-Path $PetclinicDir "examples") "workloads") "oha") "run.sh"

Push-Location $PetclinicDir
try {
    # The Joular Code Java agent is attached via the greener plugin's
    # joularCodeJavaAgentPath and joularCodeJavaConfigPath parameters.
    # The plugin passes -javaagent: to the Spring Boot JVM and automatically
    # enables the Joular Core ring buffer (-r flag) so Joular Code Java can read
    # power data. Results are written to joular-code-java-results/ in the work dir.

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
        "-Dgreener.joularCodeJavaAgentPath=$JoularCodeJavaJar",
        "-Dgreener.joularCodeJavaConfigPath=$JoularCodeJavaConfig"
    ) + $VmFlags

    Invoke-Cmd "Joular Code Java measurement" mvn $MvnArgs
} finally { Pop-Location }

# -- Display greener plugin results --------------------------------------------
Banner "GREENER PLUGIN RESULTS (process-level)"

if (Test-Path $BaselineFile) {
    $baseline = Get-Content $BaselineFile -Raw | ConvertFrom-Json
    $bEnergy   = $baseline.report.totalEnergyJoules
    $bDuration = $baseline.report.durationSeconds
    $bAvgPower = if ($bDuration -gt 0) { $bEnergy / $bDuration } else { 0 }

    Write-Output ("  Total energy : {0:F2} J" -f $bEnergy)
    Write-Output "  Duration     : $bDuration s"
    Write-Output ("  Avg power    : {0:F2} W" -f $bAvgPower)
    Write-Output "  Commit       : $CommitSha"
} else {
    Warn "Baseline file not found -- check Maven output above for errors."
}

Write-Output ""

# -- Display Joular Code Java results ------------------------------------------
Banner "JOULAR CODE JAVA RESULTS (method-level)"

# Joular Code Java writes results relative to the JVM working directory (plugin work dir)
$JoularCodeJavaResults = Join-Path (Join-Path (Join-Path $ReportsDir "oha") "work") "joular-code-java-results"

if (Test-Path $JoularCodeJavaResults) {
    Ok "Joular Code Java results found: $JoularCodeJavaResults"

    # Show filtered app results (methods matching FILTER_METHODS)
    $AppCsv = Join-Path $JoularCodeJavaResults "methods-power-app.csv"
    if (Test-Path $AppCsv) {
        Write-Output ""
        Write-Output "  Filtered methods ($FilterMethods) -- top 15 by energy:"
        Write-Output "  ------------------------------------------"
        # CSV format: timestamp,branch,power_watts,energy_joules,interval_seconds
        # Skip header, sort by energy_joules (col 4) descending
        $lines = Import-Csv -Path $AppCsv -Header @("timestamp","branch","power_watts","energy_joules","interval_seconds") |
            Select-Object -Skip 1 |
            Sort-Object { [double]$_.energy_joules } -Descending |
            Select-Object -First 15
        foreach ($line in $lines) {
            Write-Output ("    {0,-70} {1,10:F4} J" -f $line.branch, [double]$line.energy_joules)
        }
    }

    # Show all methods total
    $AllCsv = Join-Path $JoularCodeJavaResults "methods-power-all.csv"
    if (Test-Path $AllCsv) {
        Write-Output ""
        Write-Output "  All methods (unfiltered, top 20 by energy):"
        Write-Output "  ------------------------------------------"
        $lines = Import-Csv -Path $AllCsv -Header @("timestamp","branch","power_watts","energy_joules","interval_seconds") |
            Select-Object -Skip 1 |
            Sort-Object { [double]$_.energy_joules } -Descending |
            Select-Object -First 20
        foreach ($line in $lines) {
            Write-Output ("    {0,-70} {1,10:F4} J" -f $line.branch, [double]$line.energy_joules)
        }
    }
} else {
    Warn "Joular Code Java results directory not found at: $JoularCodeJavaResults"
    Warn "Joular Code Java may not have started correctly."
    Warn "Check app-stderr.log in the work dir for agent errors."
}

# -- Copy latest reports -------------------------------------------------------
$LatestReports = Join-Path $WorkDir "greener-reports-joularcode-latest"
if (Test-Path $LatestReports) { Remove-Item -Recurse -Force $LatestReports }
Copy-Item -Recurse -Force $ReportsDir $LatestReports
Info "Latest reports copied to: $LatestReports"

# -- Summary -------------------------------------------------------------------
Banner "SUMMARY"

Write-Output "  This simulation demonstrated Joular Code Java method-level energy monitoring"
Write-Output "  running alongside the greener-spring-boot Maven plugin."
Write-Output ""
Write-Output "  The greener report shows total process energy consumption."
Write-Output "  The Joular Code Java CSVs show which individual call branches"
Write-Output "  consumed the most energy."
Write-Output "  Together, they provide both a high-level overview and method-level detail."
Write-Output ""
Write-Output "Reports saved to:"
Write-Output "  Greener report            : $ReportsDir"
Write-Output "  Joular Code Java (app)    : $JoularCodeJavaResults\methods-power-app.csv"
Write-Output "  Joular Code Java (all)    : $JoularCodeJavaResults\methods-power-all.csv"
Write-Output "  Baseline JSON             : $BaselineFile"
Write-Output ""
Write-Output "Open in browser:"
Write-Output "  file:///$($ReportsDir -replace '\\', '/')/oha/greener-energy-report.html"

} finally {
    # Run cleanup
    & $cleanupBlock
}
