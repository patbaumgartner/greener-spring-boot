# ci-cpu-energy-estimator.ps1
#
# Estimates total CPU power on Windows by polling Win32_Processor.LoadPercentage
# once per second and writing (load% / 100) * TdpWatts to a plain-text file.
# Joular Core (--vm --vm-power-file) can then consume the file on the same
# machine or via a shared path.
#
# Compatible with: Windows PowerShell 5.1+ and PowerShell 7+.
# On WSL2 use the companion ci-cpu-energy-estimator.sh instead.
#
# Usage
#   Start-Process powershell -ArgumentList `
#     "-File", "ci-cpu-energy-estimator.ps1", "-OutputFile", "C:\tmp\power.txt"
#
# Or in a CI script (blocking — run as a background job):
#   $job = Start-Job { & "examples\vm-setup\ci-cpu-energy-estimator.ps1" `
#                         -OutputFile "C:\tmp\power.txt" -TdpWatts 65 }
#   # ... run measurement ...
#   Stop-Job $job; Remove-Job $job

param(
    [Parameter(Mandatory = $true)]
    [string]$OutputFile,

    [Parameter(Mandatory = $false)]
    [double]$TdpWatts = 100.0
)

$dir = Split-Path -Parent $OutputFile
if ($dir -and -not (Test-Path $dir)) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
}

Set-Content -Path $OutputFile -Value "0.000"
Write-Host "[ci-cpu-energy-estimator] output=$OutputFile  TDP=$TdpWatts W"

while ($true) {
    try {
        $load = (Get-CimInstance -ClassName Win32_Processor |
                 Measure-Object -Property LoadPercentage -Average).Average
        $power = [math]::Round($load / 100.0 * $TdpWatts, 3)
        Set-Content -Path $OutputFile -Value $power
    }
    catch {
        Set-Content -Path $OutputFile -Value "0.000"
    }
    Start-Sleep -Seconds 1
}
