# local-simulation.ps1
#
# Simulates the full energy-measurement workflow on your local machine (Windows):
#
#   1. Build greener-spring-boot plugins + Spring Petclinic
#   2. Run a baseline measurement  -> save as baseline JSON
#   3. Run a second measurement    -> compare against the baseline
#
# Both measurements happen on the same machine under identical conditions,
# so the comparison shows how reproducible the results are (expected delta ~ 0 %).
#
# Power source auto-detection (Windows):
#   1. CPU x TDP estimation via Win32_Processor.LoadPercentage
#   2. VM power file   — set VM_POWER_FILE env var to a file path
#
# Prerequisites:
#   - Java 25+ (temurin recommended)
#   - Maven
#   - git, python3 — must be on PATH
#   - Rust toolchain (cargo) — installed automatically via rustup if missing
#   - oha — downloaded automatically if missing
#
# Usage:
#   .\examples\local-simulation.ps1
#
# Environment variables (all optional — sensible defaults are used):
#   PETCLINIC_VERSION      Branch/tag to clone             (default: main)
#   JOULAR_CORE_VERSION    Joular Core release tag         (default: 0.0.1-alpha-11)
#   MEASURE_SECONDS        Measurement duration            (default: 60)
#   WARMUP_SECONDS         Warmup duration                 (default: 30)
#   THRESHOLD              Regression threshold in %       (default: 10)
#   OHA_VERSION            oha release version             (default: 1.14.0)
#   TDP_WATTS              TDP for CPU estimation          (default: 100)
#   VM_POWER_FILE          VM power file path              (default: unset)
#   WORK_DIR               Temporary working directory     (default: $env:TEMP\greener-local-sim)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ── Configuration ─────────────────────────────────────────────────────────────
$PetclinicVersion   = if ($env:PETCLINIC_VERSION)   { $env:PETCLINIC_VERSION }   else { "main" }
$JoularCoreVersion  = if ($env:JOULAR_CORE_VERSION) { $env:JOULAR_CORE_VERSION } else { "0.0.1-alpha-11" }
$MeasureSeconds     = if ($env:MEASURE_SECONDS)     { $env:MEASURE_SECONDS }     else { "60" }
$WarmupSeconds      = if ($env:WARMUP_SECONDS)      { $env:WARMUP_SECONDS }      else { "30" }
$Threshold          = if ($env:THRESHOLD)            { $env:THRESHOLD }            else { "10" }
$OhaVersion         = if ($env:OHA_VERSION)          { $env:OHA_VERSION }          else { "1.14.0" }
$TdpWatts           = if ($env:TDP_WATTS)            { $env:TDP_WATTS }            else { "100" }
$WorkDir            = if ($env:WORK_DIR)             { $env:WORK_DIR }             else { Join-Path $env:TEMP "greener-local-sim" }

$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path (Join-Path $ScriptDir "..")).Path
$CommitSha   = try { git -C $ProjectRoot rev-parse HEAD 2>$null } catch { "unknown" }
$Branch      = try { git -C $ProjectRoot rev-parse --abbrev-ref HEAD 2>$null } catch { "unknown" }
if (-not $CommitSha) { $CommitSha = "unknown" }
if (-not $Branch)    { $Branch = "unknown" }

$PetclinicDir      = Join-Path $WorkDir "spring-petclinic"
$BaselineFile      = Join-Path $WorkDir "energy-baseline.json"
$ReportsBaseline   = Join-Path $WorkDir "greener-reports-baseline"
$ReportsComparison = Join-Path $WorkDir "greener-reports-comparison"
$CiPowerFile       = Join-Path $WorkDir "ci-power.txt"
$JoularCacheDir    = Join-Path $HOME ".greener" "cache" "joularcore"

$CiEstimatorJob = $null

# ── Helpers ───────────────────────────────────────────────────────────────────
function Info($msg)   { Write-Host "i  $msg" }
function Ok($msg)     { Write-Host "[OK] $msg" -ForegroundColor Green }
function Warn($msg)   { Write-Host "[!!] $msg" -ForegroundColor Yellow }
function Banner($msg) {
    Write-Host ""
    Write-Host ("=" * 60)
    Write-Host "  $msg"
    Write-Host ("=" * 60)
}

function Invoke-Cmd {
    param([string]$Description, [string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

# ── Cleanup on exit ──────────────────────────────────────────────────────────
$cleanupBlock = {
    if ($script:CiEstimatorJob) {
        Stop-Job $script:CiEstimatorJob -ErrorAction SilentlyContinue
        Remove-Job $script:CiEstimatorJob -Force -ErrorAction SilentlyContinue
        Info "Stopped CI power estimator job"
    }
}

try {

# ── Preflight checks ─────────────────────────────────────────────────────────
Banner "Preflight checks"

foreach ($tool in @("java", "mvn", "git")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool not found on PATH. Please install it first."
    }
}

java -version 2>&1 | Select-Object -First 1 | Write-Host
mvn --version 2>&1 | Select-Object -First 1 | Write-Host

if (-not (Test-Path $WorkDir)) {
    New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null
}

# ── Build greener-spring-boot plugins ─────────────────────────────────────────
Banner "Building greener-spring-boot plugins"

Push-Location $ProjectRoot
try {
    Invoke-Cmd "Maven build" mvn @("--batch-mode", "--no-transfer-progress", "clean", "install", "-DskipTests")
} finally { Pop-Location }

# ── Clone & build Spring Petclinic ────────────────────────────────────────────
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

# ── Install oha ──────────────────────────────────────────────────────────────
Banner "Checking oha $OhaVersion"

if (Get-Command oha -ErrorAction SilentlyContinue) {
    Ok "oha already installed: $(oha --version)"
} else {
    Info "Installing oha $OhaVersion..."
    $OhaUrl = "https://github.com/hatoo/oha/releases/download/v$OhaVersion/oha-windows-amd64.exe"
    $OhaBin = Join-Path $WorkDir "oha.exe"
    Invoke-WebRequest -Uri $OhaUrl -OutFile $OhaBin -UseBasicParsing
    $env:PATH = "$WorkDir;$env:PATH"
    Ok "oha installed: $(oha --version)"
}

# ── Copy workload scripts ────────────────────────────────────────────────────
$ExamplesTarget = Join-Path $PetclinicDir "examples"
if (Test-Path $ExamplesTarget) { Remove-Item -Recurse -Force $ExamplesTarget }
Copy-Item -Recurse -Force (Join-Path $ProjectRoot "examples") $ExamplesTarget

# ── Power source detection ───────────────────────────────────────────────────
Banner "Detecting power source"

$PowerSource = "none"

if ($env:VM_POWER_FILE -and (Test-Path $env:VM_POWER_FILE)) {
    $PowerSource = "vm-file"
    Ok "VM power file found at $($env:VM_POWER_FILE)."
} elseif (Get-CimInstance -ClassName Win32_Processor -ErrorAction SilentlyContinue) {
    $PowerSource = "ci-estimated"
    Info "Using CPU load x TDP software estimation via Win32_Processor."
} else {
    Warn "No power source — measurement will be skipped."
}

if ($PowerSource -eq "none") {
    Write-Host ""
    Write-Host "No usable power source detected. Exiting."
    exit 0
}

# ── Start CI CPU power estimator (if needed) ──────────────────────────────────
if ($PowerSource -eq "ci-estimated") {
    Info "Starting CI CPU power estimator (TDP=$TdpWatts W)..."
    $EstimatorScript = Join-Path $ProjectRoot "examples" "vm-setup" "ci-cpu-energy-estimator.ps1"
    $CiEstimatorJob = Start-Job -ScriptBlock {
        param($Script, $OutFile, $Tdp)
        & $Script -OutputFile $OutFile -TdpWatts $Tdp
    } -ArgumentList $EstimatorScript, $CiPowerFile, $TdpWatts
    $env:VM_POWER_FILE = $CiPowerFile
    Start-Sleep -Seconds 2
    if (Test-Path $CiPowerFile) {
        Info "Estimator running — current estimate: $(Get-Content $CiPowerFile) W"
    }
}

# ── Joular Core ──────────────────────────────────────────────────────────────
Banner "Preparing Joular Core $JoularCoreVersion"

$JoularCoreBinary = Join-Path $JoularCacheDir "joularcore-windows-x86_64.exe"

if (Test-Path $JoularCoreBinary) {
    Ok "Joular Core found in cache: $JoularCoreBinary"
} else {
    Info "Building Joular Core from source..."
    if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
        Info "cargo not found — installing Rust toolchain via rustup..."
        $RustupInit = Join-Path $WorkDir "rustup-init.exe"
        Invoke-WebRequest -Uri "https://win.rustup.rs/x86_64" -OutFile $RustupInit -UseBasicParsing
        & $RustupInit -y
        $env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
        Ok "Rust toolchain installed: $(cargo --version)"
    }

    $JoularSrc = Join-Path $WorkDir "joularcore-src"
    if (Test-Path $JoularSrc) { Remove-Item -Recurse -Force $JoularSrc }
    Invoke-Cmd "Clone Joular Core" git @("clone", "--depth", "1", "--branch", $JoularCoreVersion,
        "https://github.com/joular/joularcore.git", $JoularSrc)

    Push-Location $JoularSrc
    try {
        Invoke-Cmd "Build Joular Core" cargo @("build", "--release")
    } finally { Pop-Location }

    if (-not (Test-Path $JoularCacheDir)) {
        New-Item -ItemType Directory -Path $JoularCacheDir -Force | Out-Null
    }
    Copy-Item (Join-Path $JoularSrc "target" "release" "joularcore.exe") $JoularCoreBinary
    Ok "Joular Core built and cached."
}

# ── VM flags ──────────────────────────────────────────────────────────────────
$VmFlags = @()
if ($PowerSource -eq "vm-file" -or $PowerSource -eq "ci-estimated") {
    $VmFlags = @("-Dgreener.vmMode=true", "-Dgreener.vmPowerFilePath=$($env:VM_POWER_FILE)")
}

# ── Run 1: Baseline measurement ──────────────────────────────────────────────
Banner "RUN 1 - BASELINE"

Push-Location $PetclinicDir
try {
    $Jar = Get-ChildItem -Path "target" -Filter "*.jar" -Recurse |
           Where-Object { $_.Name -notlike "*-sources.jar" } |
           Select-Object -First 1 -ExpandProperty FullName

    $OhaScript = Join-Path $PetclinicDir "examples" "workloads" "oha" "run.sh"

    $MvnArgs = @(
        "--batch-mode", "--no-transfer-progress",
        "com.patbaumgartner:greener-spring-boot-maven-plugin:0.1.0-SNAPSHOT:measure",
        "-Dgreener.springBootJar=$Jar",
        "-Dgreener.joularCoreBinaryPath=$JoularCoreBinary",
        "-Dgreener.baseUrl=http://localhost:8080",
        "-Dgreener.externalTrainingScriptFile=$OhaScript",
        "-Dgreener.warmupDurationSeconds=$WarmupSeconds",
        "-Dgreener.measureDurationSeconds=$MeasureSeconds",
        "-Dgreener.requestsPerSecond=20",
        "-Dgreener.healthCheckPath=/actuator/health/readiness",
        "-Dgreener.baselineFile=$BaselineFile",
        "-Dgreener.failOnRegression=false",
        "-Dgreener.reportOutputDir=$ReportsBaseline"
    ) + $VmFlags

    Invoke-Cmd "Baseline measurement" mvn $MvnArgs
} finally { Pop-Location }

# ── Promote Run 1 to baseline ────────────────────────────────────────────────
Banner "Promoting Run 1 to baseline"

Push-Location $PetclinicDir
try {
    Invoke-Cmd "Update baseline" mvn @(
        "--batch-mode", "--no-transfer-progress",
        "com.patbaumgartner:greener-spring-boot-maven-plugin:0.1.0-SNAPSHOT:update-baseline",
        "-Dgreener.baselineFile=$BaselineFile",
        "-Dgreener.latestReportFile=$(Join-Path $ReportsBaseline 'latest-energy-report.json')",
        "-Dgreener.commitSha=$CommitSha",
        "-Dgreener.branch=$Branch"
    )
} finally { Pop-Location }

Write-Host ""
Write-Host "Baseline created:"
$baseline = Get-Content $BaselineFile -Raw | ConvertFrom-Json
$bEnergy  = $baseline.report.totalEnergyJoules
Write-Host ("  Energy : {0:F4} J" -f $bEnergy)
Write-Host "  Commit : $CommitSha"

# ── Run 2: Comparison measurement ────────────────────────────────────────────
Banner "RUN 2 - COMPARISON (vs baseline from Run 1)"

Push-Location $PetclinicDir
try {
    $MvnArgs = @(
        "--batch-mode", "--no-transfer-progress",
        "com.patbaumgartner:greener-spring-boot-maven-plugin:0.1.0-SNAPSHOT:measure",
        "-Dgreener.springBootJar=$Jar",
        "-Dgreener.joularCoreBinaryPath=$JoularCoreBinary",
        "-Dgreener.baseUrl=http://localhost:8080",
        "-Dgreener.externalTrainingScriptFile=$OhaScript",
        "-Dgreener.warmupDurationSeconds=$WarmupSeconds",
        "-Dgreener.measureDurationSeconds=$MeasureSeconds",
        "-Dgreener.requestsPerSecond=20",
        "-Dgreener.healthCheckPath=/actuator/health/readiness",
        "-Dgreener.baselineFile=$BaselineFile",
        "-Dgreener.threshold=$Threshold",
        "-Dgreener.failOnRegression=false",
        "-Dgreener.reportOutputDir=$ReportsComparison"
    ) + $VmFlags

    Invoke-Cmd "Comparison measurement" mvn $MvnArgs
} finally { Pop-Location }

# ── Summary ──────────────────────────────────────────────────────────────────
Banner "RESULTS - Baseline vs Comparison"

$baseline   = Get-Content $BaselineFile -Raw | ConvertFrom-Json
$comparison = Get-Content (Join-Path $ReportsComparison "latest-energy-report.json") -Raw | ConvertFrom-Json

$bEnergy = $baseline.report.totalEnergyJoules
$cEnergy = $comparison.report.totalEnergyJoules
$delta   = $cEnergy - $bEnergy
$pct     = if ($bEnergy -gt 0) { $delta / $bEnergy * 100 } else { 0 }

Write-Host ("  Baseline energy  : {0:F4} J" -f $bEnergy)
Write-Host ("  Comparison energy: {0:F4} J" -f $cEnergy)
Write-Host ("  Delta            : {0:+0.0000;-0.0000} J ({1:+0.00;-0.00} %)" -f $delta, $pct)
Write-Host "  Threshold        : +/-$Threshold %"
Write-Host ""

if ([Math]::Abs($pct) -le [double]$Threshold) {
    Write-Host "  [OK] Within threshold - results are reproducible." -ForegroundColor Green
} else {
    Write-Host "  [!!] Outside threshold - noisy environment or real regression." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Reports saved to:"
Write-Host "  Baseline   : $ReportsBaseline\"
Write-Host "  Comparison : $ReportsComparison\"
Write-Host "  Baseline JSON: $BaselineFile"

} finally {
    # Run cleanup
    & $cleanupBlock
}
