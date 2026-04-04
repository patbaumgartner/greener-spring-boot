# Real Power Measurement in Virtualised Environments

greener-spring-boot uses
[Joular Core](https://www.noureddine.org/research/joular/joularcore) to measure
CPU energy consumption.  Joular Core reads hardware power counters (Intel/AMD RAPL
via the Linux `powercap` subsystem or the
[Hubblo RAPL driver](https://github.com/hubblo-org/windows-rapl-driver) on
Windows).  Inside a virtual machine the guest kernel cannot access RAPL directly
because the hypervisor does not expose those MSRs.

The recommended solutions, from simplest setup to most portable, are:

| Option | Environment | Accuracy |
|---|---|---|
| **A** — RAPL MSR passthrough | KVM guest with passthrough | ★★★ high |
| **B** — Windows RAPL driver | Windows bare-metal (Intel/AMD) | ★★★ high |
| **C** — Scaphandre virtio-mem | KVM guest + Scaphandre on host | ★★★ highest |
| **D** — Scaphandre file exporter | KVM guest via virtio-fs / NFS | ★★★ high |
| **E** — CPU-time × TDP estimation | Any Linux/Windows (CI, WSL2, VMs) | ★★ estimated |

> **Important**: Options A–D all rely on real hardware power counters.
> Option E uses a software model — results are reproducible on the same
> runner/hardware but not hardware-calibrated.  Only use Option E for
> relative comparisons between commits; use A–D for absolute accuracy.

---

## Architecture overview

```
KVM Host (bare-metal, Intel/AMD with RAPL)
├── RAPL counters  (/sys/class/powercap/intel-rapl)
├── Scaphandre     reads RAPL, computes per-VM power
│   └── qemu exporter  writes power to per-VM virtio-mem shmem region
│                       OR to a file on a shared virtio-fs mount
│
└── KVM Guest (your VM)
    ├── Joular Core  --vm --vm-power-file /mnt/host-power/vm-power.txt
    └── Spring Boot application under test
```

---

## Option A — RAPL MSR passthrough (simplest)

Some hypervisors allow passing RAPL MSRs through to the guest.  When this works,
no VM mode is needed at all — Joular Core reads RAPL directly as if on bare metal.

Requirements:
- KVM with `msr` module loaded (`modprobe msr`)
- Guest kernel with `intel_rapl_msr` or `intel_rapl_common` modules
- QEMU option: `-cpu host` (exposes host CPU features) or explicit MSR passthrough

```bash
# Check inside the VM
ls /sys/class/powercap/intel-rapl
# If this directory exists, use Joular Core without --vm
```

```xml
<!-- Maven — no VM mode needed -->
<vmMode>false</vmMode>
```

---

## Option B — Windows RAPL driver (bare-metal Windows)

On bare-metal Windows machines with Intel or AMD CPUs, Joular Core can read
hardware RAPL counters directly — no VM mode or TDP estimation needed — once
[Hubblo's Windows RAPL driver](https://github.com/hubblo-org/windows-rapl-driver)
is installed.

### Installation

The easiest way to install the signed driver is through the Scaphandre installer:

1. Download the installer from the
   [Scaphandre v1.0.0 release](https://github.com/hubblo-org/scaphandre/releases/download/v1.0.0/scaphandre_v1.0.0_installer.exe).
2. Run `scaphandre_v1.0.0_installer.exe` — this installs and registers the
   `ScaphandreDrv` kernel driver.
3. Verify the driver is running:
   ```powershell
   driverquery /v | Select-String "Scaph"
   ```
   You should see a line with `ScaphandreDrv` in `Running` state.

Once the driver is installed, Joular Core reads RAPL without administrator
rights.  GPU power is also available via `nvidia-smi` / `amd-smi`.

### Plugin configuration (no VM mode)

```xml
<!-- Maven — no VM mode needed, RAPL is read directly -->
<vmMode>false</vmMode>
```

```kotlin
// Gradle — no VM mode needed
vmMode = false
```

### Checking RAPL availability

```powershell
# Verify the ScaphandreDrv service is running
sc.exe query ScaphandreDrv
```

If the driver is not installed, the `local-simulation.ps1` script falls back to
the CPU-time × TDP software estimation (Option E) automatically.

---

## Option C — Scaphandre virtio-mem exporter (recommended for KVM)

Scaphandre's `qemu` exporter uses a shared memory device (virtio-mem) to push
per-VM power data directly into the guest's address space.  Joular Core reads
this without any host-accessible file.

### Host setup

```bash
# 1. Install Scaphandre on the KVM host
#    https://hubblo-org.github.io/scaphandre-documentation/tutorials/installation.html

# Debian/Ubuntu
curl -fsSL https://packages.hubblo.org/debian/repo-key.gpg \
  | sudo gpg --dearmor -o /usr/share/keyrings/hubblo.gpg
echo "deb [signed-by=/usr/share/keyrings/hubblo.gpg] \
  https://packages.hubblo.org/debian stable main" \
  | sudo tee /etc/apt/sources.list.d/hubblo.list
sudo apt-get update && sudo apt-get install scaphandre

# 2. Start Scaphandre with the qemu exporter
#    This exposes per-VM power to each guest via its shared memory region.
sudo scaphandre qemu
```

### Guest setup (inside the VM)

Joular Core reads the shared memory region automatically when `--vm` is passed
without `--vm-power-file`:

```bash
# Joular Core reads from the virtio-mem region:
joularcore --vm -p <PID> -c cpu -f output.csv -s
```

In the greener-spring-boot plugin:

```xml
<!-- Maven — vm-power-file is NOT needed when using virtio-mem -->
<vmMode>true</vmMode>
```

```kotlin
// Gradle
vmMode = true
```

---

## Option D — Scaphandre file exporter over virtio-fs

Use this when virtio-mem is not available or when you need to share the power
value via a regular file (e.g. Proxmox, older QEMU versions).

### Host setup

```bash
# 1. Install Scaphandre (see Option C)

# 2. Create the shared directory
sudo mkdir -p /srv/greener-power-share

# 3. Export power to a file for each VM
#    Scaphandre writes <vm-name>.txt with the current Watt value every second.
sudo scaphandre json \
  --process-regex "qemu.*myvm" \
  --step 1 \
  --output /srv/greener-power-share/vm-power.json &

# 4. Parse the JSON and write a plain Watt value for Joular Core
#    Run this helper on the host alongside Scaphandre:
bash examples/vm-setup/scaphandre/host-power-to-file.sh \
  /srv/greener-power-share/vm-power.json \
  /srv/greener-power-share/vm-power.txt &
```

Then mount `/srv/greener-power-share` inside the VM using virtio-fs or NFS:

```bash
# In the VM (assuming the host exported /srv/greener-power-share via NFS):
sudo mount -t nfs host-ip:/srv/greener-power-share /mnt/host-power
```

In the greener-spring-boot plugin:

```xml
<!-- Maven -->
<vmMode>true</vmMode>
<vmPowerFilePath>/mnt/host-power/vm-power.txt</vmPowerFilePath>
```

```kotlin
// Gradle
vmMode = true
vmPowerFilePath = file("/mnt/host-power/vm-power.txt")
```

---

## Option E — CPU-time × TDP estimation (CI/CD, WSL2, any Linux/Windows VM)

Use this when none of the hardware-backed options above are available — for
example on GitHub-hosted runners, GitLab shared runners, Jenkins agents,
WSL2, or any Linux/Windows VM where hardware RAPL access is not available.

### How it works

The script [`ci-cpu-energy-estimator.sh`](ci-cpu-energy-estimator.sh) reads
CPU jiffies from `/proc/stat` every second, computes:

```
cpu_utilisation = 1 − (Δidle_jiffies / Δtotal_jiffies)
estimated_power = cpu_utilisation × TDP_W
```

and writes the result to a plain-text file.  Joular Core is then started in
`--vm --vm-power-file` mode, reading that file as the total VM power and
attributing a fraction to the monitored PID based on its own CPU share.

### TDP guidance

| Runner / Hardware | Suggested TDP_WATTS |
|---|---|
| GitHub Actions `ubuntu-latest` (Intel Xeon Platinum 8370C, 2–4 vCPU) | 100 |
| GitLab/Jenkins self-hosted (server CPU) | actual CPU TDP |
| WSL2 on a laptop (Intel Core i7 28 W) | 28–45 |
| WSL2 on a desktop (Intel Core i9 125 W) | 125 |

### Linux / macOS usage

```bash
# Start the estimator in the background
bash examples/vm-setup/ci-cpu-energy-estimator.sh /tmp/ci-power.txt 100 &
ESTIMATOR_PID=$!

# Run greener-spring-boot in VM mode
mvn greener:measure \
  -Dgreener.vmMode=true \
  -Dgreener.vmPowerFilePath=/tmp/ci-power.txt \
  -Dgreener.springBootJar=target/myapp.jar

# Stop the estimator (send SIGTERM to the background PID)
kill -TERM $ESTIMATOR_PID
```

### Windows usage

```powershell
# Start the estimator as a background job
$job = Start-Job {
    & "$using:PWD\examples\vm-setup\ci-cpu-energy-estimator.ps1" `
        -OutputFile "C:\tmp\ci-power.txt" -TdpWatts 65
}

# Run greener-spring-boot in VM mode
mvn greener:measure `
  -Dgreener.vmMode=true `
  -Dgreener.vmPowerFilePath="C:\tmp\ci-power.txt" `
  -Dgreener.springBootJar="target\myapp.jar"

# Stop the estimator
Stop-Job $job; Remove-Job $job
```

### WSL2 usage

On WSL2, use the Linux script (`ci-cpu-energy-estimator.sh`) — it reads
`/proc/stat` from the WSL2 kernel just like any other Linux environment.

```bash
bash examples/vm-setup/ci-cpu-energy-estimator.sh /tmp/ci-power.txt 45 &
ESTIMATOR_PID=$!
# … run measurement …
kill -TERM $ESTIMATOR_PID
```

### Plugin configuration (VM mode)

```xml
<!-- Maven -->
<vmMode>true</vmMode>
<vmPowerFilePath>/tmp/ci-power.txt</vmPowerFilePath>
```

```kotlin
// Gradle
vmMode = true
vmPowerFilePath = file("/tmp/ci-power.txt")
```

---

## Proxmox notes

Proxmox VE uses QEMU/KVM.  Scaphandre works the same way as described in
Options C and D.

1. Install Scaphandre on the Proxmox host node.
2. Use the `qemu` exporter (Option A) for automatic per-VM power exposure via
   virtio-mem.
3. The VM ID in Proxmox maps to the QEMU process name — Scaphandre identifies
   each VM automatically.

---

## CI / CD

Energy measurement runs automatically in all supported CI systems via the
CPU-time × TDP estimation (Option D) when no hardware RAPL access is available.
No configuration is needed on GitHub-hosted runners, GitLab shared runners, or
standard Jenkins agents — the pipeline detects the best available power source
at runtime.

See the individual CI configs for details:

- **GitHub Actions** — `.github/workflows/energy-baseline.yml` and
  `.github/workflows/energy-comparison.yml`

The plugin itself is CI-agnostic — use it in any pipeline that can run Maven or
Gradle. Adapt the GitHub Actions workflow steps for GitLab CI/CD, Jenkins, or
other CI systems as needed.

For hardware-accurate measurements use a self-hosted bare-metal runner or
configure Scaphandre on your KVM host (Options A or B above).

> **Note**: Option D results are reproducible on the same runner/hardware but
> not hardware-calibrated.  For research or production comparisons that need
> absolute accuracy, use Options A, B, or C.
