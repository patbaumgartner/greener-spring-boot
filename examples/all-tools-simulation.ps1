# all-tools-simulation.ps1
#
# Runs an energy measurement for every installed workload tool against
# Spring Petclinic, then prints a summary comparison across all tools.
#
# This is the multi-tool counterpart of local-simulation.ps1.  Instead of
# running a single tool twice (baseline + comparison), it runs every
# detected tool once and collects the per-tool energy reports so they can
# be compared side-by-side.
#
# Supported tools (skipped automatically if not installed):
#   oha, wrk, wrk2, bombardier, ab, k6, gatling, locust
#
# Power source auto-detection (Windows):
#   1. Hubblo RAPL driver (ScaphandreDrv) -- direct hardware RAPL reading
#   2. CPU x TDP estimation via Win32_Processor.LoadPercentage
#   3. VM power file   -- set VM_POWER_FILE env var to a file path
#
# Prerequisites:
#   - Java 17+
#   - Maven
#   - git -- must be on PATH
#   - At least one workload tool installed (oha, wrk, ...)
#
# Usage:
#   .\examples\all-tools-simulation.ps1
#
# Environment variables (all optional -- sensible defaults are used):
#   PETCLINIC_VERSION      Branch/tag to clone             (default: main)
#   JOULAR_CORE_VERSION    Joular Core release tag         (default: 0.0.1-alpha-11)
#   MEASURE_SECONDS        Measurement duration            (default: 60)
#   WARMUP_SECONDS         Warmup duration                 (default: 30)
#   THRESHOLD              Regression threshold in %       (default: 10)
#   TDP_WATTS              TDP for CPU estimation          (default: 100)
#   VM_POWER_FILE          VM power file path              (default: unset)
#   WORK_DIR               Temporary working directory     (default: $env:TEMP\greener-all-tools)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# PowerShell 5.x defaults to TLS 1.0/1.1; GitHub requires TLS 1.2+
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# -- Configuration -------------------------------------------------------------
$PetclinicVersion   = if ($env:PETCLINIC_VERSION)   { $env:PETCLINIC_VERSION }   else { "main" }
$JoularCoreVersion  = if ($env:JOULAR_CORE_VERSION) { $env:JOULAR_CORE_VERSION } else { "0.0.1-alpha-11" }
$MeasureSeconds     = if ($env:MEASURE_SECONDS)     { $env:MEASURE_SECONDS }     else { "60" }
$WarmupSeconds      = if ($env:WARMUP_SECONDS)      { $env:WARMUP_SECONDS }      else { "30" }
$Threshold          = if ($env:THRESHOLD)            { $env:THRESHOLD }            else { "10" }
$TdpWatts           = if ($env:TDP_WATTS)            { $env:TDP_WATTS }            else { "100" }
$WorkDir            = if ($env:WORK_DIR)             { $env:WORK_DIR }             else { Join-Path $env:TEMP "greener-all-tools" }

$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptDir "..")).Path

$PetclinicDir      = Join-Path $WorkDir "spring-petclinic"
$CiPowerFile       = Join-Path $WorkDir "ci-power.txt"
$JoularCacheDir    = Join-Path (Join-Path (Join-Path $HOME ".greener") "cache") "joularcore"

$CiEstimatorJob = $null

# -- Tool list & binary mapping ------------------------------------------------
$ToolList = @("oha", "wrk", "wrk2", "bombardier", "ab", "k6", "locust", "gatling")

function Get-ToolBinary($tool) {
    switch ($tool) {
        "oha"        { "oha" }
        "wrk"        { "wrk" }
        "wrk2"       { "wrk2" }
        "k6"         { "k6" }
        "ab"         { "ab" }
        "bombardier" { "bombardier" }
        "locust"     { "locust" }
        "gatling"    { "java" }
        default      { "" }
    }
}

# -- Helpers -------------------------------------------------------------------
function Info($msg)   { Write-Output "i  $msg" }
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

# Detect available tools
$AvailableTools = @()
foreach ($t in $ToolList) {
    $bin = Get-ToolBinary $t
    if ($bin -and (Get-Command $bin -ErrorAction SilentlyContinue)) {
        $AvailableTools += $t
    }
}

if ($AvailableTools.Count -eq 0) {
    throw "No workload tools found. Install at least one of: $($ToolList -join ', ')"
}

Ok "Available tools: $($AvailableTools -join ', ')"

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

# -- Joular Core --------------------------------------------------------------
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
        # Ensure MinGW build tools are available (needed by the GNU Rust target)
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

# -- VM flags ------------------------------------------------------------------
$VmFlags = @()
if ($PowerSource -eq "vm-file" -or $PowerSource -eq "ci-estimated") {
    $VmFlags = @("-Dgreener.vmMode=true", "-Dgreener.vmPowerFilePath=$($env:VM_POWER_FILE)")
}

# -- Run each tool -------------------------------------------------------------
$Passed  = @()
$Failed  = @()
$Skipped = @()

foreach ($tool in $ToolList) {
    $bin = Get-ToolBinary $tool

    if (-not $bin -or -not (Get-Command $bin -ErrorAction SilentlyContinue)) {
        $Skipped += $tool
        continue
    }

    $ToolScript = Join-Path (Join-Path (Join-Path (Join-Path $PetclinicDir "examples") "workloads") $tool) "run.sh"
    if (-not (Test-Path $ToolScript)) {
        $Skipped += $tool
        continue
    }

    $ReportsDir = Join-Path $WorkDir "greener-reports-$tool"

    Banner "Measuring with $tool"

    Push-Location $PetclinicDir
    try {
        $MvnArgs = @(
            "--batch-mode", "--no-transfer-progress",
            "com.patbaumgartner:greener-spring-boot-maven-plugin:0.2.0-SNAPSHOT:measure",
            "-Dgreener.joularCoreBinaryPath=$JoularCoreBinary",
            "-Dgreener.baseUrl=http://localhost:8080",
            "-Dgreener.externalTrainingScriptFile=$ToolScript",
            "-Dgreener.warmupDurationSeconds=$WarmupSeconds",
            "-Dgreener.measureDurationSeconds=$MeasureSeconds",
            "-Dgreener.requestsPerSecond=20",
            "-Dgreener.healthCheckPath=/actuator/health/readiness",
            "-Dgreener.failOnRegression=false",
            "-Dgreener.reportOutputDir=$ReportsDir"
        ) + $VmFlags

        Invoke-Cmd "$tool measurement" mvn $MvnArgs
        $Passed += $tool
        Ok "$tool measurement complete -> $ReportsDir\"
    } catch {
        $Failed += $tool
        Warn "$tool measurement failed: $($_.Exception.Message)"
    } finally { Pop-Location }
}

# -- Summary ------------------------------------------------------------------
Banner "All-Tools Summary"

Write-Output ""
Write-Output ("  {0,-12}  {1,-10}  {2}" -f "TOOL", "STATUS", "ENERGY")
Write-Output ("  {0,-12}  {1,-10}  {2}" -f ("─" * 12), ("─" * 10), ("─" * 34))

foreach ($tool in $ToolList) {
    $ReportsDir = Join-Path $WorkDir "greener-reports-$tool"
    $ReportJson = Join-Path $ReportsDir "latest-energy-report.json"
    if ($Passed -contains $tool) {
        $Energy = "—"
        if (Test-Path $ReportJson) {
            try {
                $report = Get-Content $ReportJson -Raw | ConvertFrom-Json
                $Energy = "{0:F2} J" -f $report.report.totalEnergyJoules
            } catch {}
        }
        Write-Output ("  {0,-12}  ✅ passed   {1}" -f $tool, $Energy)
    } elseif ($Failed -contains $tool) {
        Write-Output ("  {0,-12}  ❌ failed   —" -f $tool)
    } else {
        Write-Output ("  {0,-12}  ⏭  skipped  (not installed)" -f $tool)
    }
}

Write-Output ""
Write-Output "  Passed: $($Passed.Count)  |  Failed: $($Failed.Count)  |  Skipped: $($Skipped.Count)"
Write-Output ""
Write-Output "  Reports saved under: $WorkDir\greener-reports-*\"

if ($Failed.Count -gt 0) {
    exit 1
}

} finally {
    # Run cleanup
    & $cleanupBlock
}
