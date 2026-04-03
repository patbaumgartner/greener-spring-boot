package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.comparator.EnergyComparator;
import com.patbaumgartner.greener.core.config.JoularCoreConfig;
import com.patbaumgartner.greener.core.config.TrainingConfig;
import com.patbaumgartner.greener.core.downloader.JoularCoreDownloader;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import com.patbaumgartner.greener.core.reader.JoularCoreResultReader;
import com.patbaumgartner.greener.core.reporter.ConsoleReporter;
import com.patbaumgartner.greener.core.reporter.HtmlReporter;
import com.patbaumgartner.greener.core.runner.ApplicationRunner;
import com.patbaumgartner.greener.core.runner.JoularCoreRunner;
import com.patbaumgartner.greener.core.runner.TrainingRunner;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Gradle task that measures the energy consumption of a Spring Boot application
 * using <a href="https://www.noureddine.org/research/joular/joularcore">Joular
 * Core</a>.
 *
 * <p>
 * This task mirrors the Maven {@code greener:measure} goal.
 */
@DisableCachingByDefault(because = "Energy measurement depends on live system state")
public abstract class MeasureEnergyTask extends DefaultTask {

    /**
     * Path to the executable Spring Boot fat-jar.
     * 
     * @return the Spring Boot jar property
     */
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract RegularFileProperty getSpringBootJar();

    /**
     * HTTP port the Spring Boot application listens on.
     * 
     * @return the application port property
     */
    @Input
    public abstract Property<Integer> getApplicationPort();

    /**
     * Full path to the Joular Core binary (optional; auto-downloaded when absent).
     * 
     * @return the Joular Core binary path property
     */
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getJoularCoreBinaryPath();

    /**
     * Joular Core release version to download.
     * 
     * @return the Joular Core version property
     */
    @Input
    public abstract Property<String> getJoularCoreVersion();

    /**
     * Hardware component to monitor: {@code cpu}, {@code gpu}, or {@code all}.
     * 
     * @return the Joular Core component property
     */
    @Input
    public abstract Property<String> getJoularCoreComponent();

    /**
     * Base URL of the Spring Boot application.
     * 
     * @return the base URL property
     */
    @Input
    public abstract Property<String> getBaseUrl();

    /**
     * Relative URL paths exercised during the training run.
     * 
     * @return the training paths property
     */
    @Input
    public abstract ListProperty<String> getTrainingPaths();

    /**
     * Requests per second issued during the training run.
     * 
     * @return the requests per second property
     */
    @Input
    public abstract Property<Integer> getRequestsPerSecond();

    /**
     * Optional external training command.
     * 
     * @return the external training command property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getExternalTrainingCommand();

    /**
     * Path to an external shell script used as the training workload.
     * 
     * @return the external training script file property
     */
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getExternalTrainingScriptFile();

    /**
     * Enable VM mode (no direct RAPL; reads power from
     * {@link #getVmPowerFilePath()}).
     * 
     * @return the VM mode property
     */
    @Input
    public abstract Property<Boolean> getVmMode();

    /**
     * Path to the file providing VM power in Watts (VM mode only).
     * 
     * @return the VM power file path property
     */
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getVmPowerFilePath();

    /**
     * Warmup duration in seconds.
     * 
     * @return the warmup duration property
     */
    @Input
    public abstract Property<Integer> getWarmupDurationSeconds();

    /**
     * Measurement duration in seconds.
     * 
     * @return the measure duration property
     */
    @Input
    public abstract Property<Integer> getMeasureDurationSeconds();

    /**
     * Seconds to wait for application startup.
     * 
     * @return the startup timeout property
     */
    @Input
    public abstract Property<Integer> getStartupTimeoutSeconds();

    /**
     * Health-check path used to detect application readiness.
     * 
     * @return the health check path property
     */
    @Input
    public abstract Property<String> getHealthCheckPath();

    /**
     * Path to the JSON baseline file.
     * 
     * @return the baseline file property
     */
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getBaselineFile();

    /**
     * Maximum percentage energy increase before the build is failed.
     * 
     * @return the threshold property
     */
    @Input
    public abstract Property<Double> getThreshold();

    /**
     * Whether to fail the build on energy regression.
     * 
     * @return the fail on regression property
     */
    @Input
    public abstract Property<Boolean> getFailOnRegression();

    /**
     * Directory where the HTML report is written.
     * 
     * @return the report output directory property
     */
    @OutputDirectory
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getReportOutputDir();

    /**
     * Runs the energy measurement workflow.
     * 
     * @throws Exception if measurement fails
     */
    @TaskAction
    public void measureEnergy() throws Exception {
        File springBootJarFile = getSpringBootJar().get().getAsFile();
        if (!springBootJarFile.exists()) {
            throw new GradleException("Spring Boot jar not found: " + springBootJarFile
                    + ". Build the jar first (e.g. with 'bootJar').");
        }

        // Resolve Joular Core binary
        Path joularCoreBinary = resolveJoularCoreBinary();

        int warmup = getWarmupDurationSeconds().get();
        int measure = getMeasureDurationSeconds().get();
        String baseUrl = getBaseUrl().get();

        Path reportDir = resolveReportDir();
        Path workingDir = reportDir.resolve("work");

        // Start application
        ApplicationRunner appRunner = new ApplicationRunner();
        getLogger().lifecycle("Starting Spring Boot application: " + springBootJarFile);

        // Enable health probes so /actuator/health/readiness is available
        List<String> effectiveAppArgs = new ArrayList<>();
        effectiveAppArgs.add("--management.endpoint.health.probes.enabled=true");

        Process appProcess = appRunner.start(
                springBootJarFile.toPath(),
                null, null,
                workingDir,
                null, effectiveAppArgs);

        Path outputCsv = workingDir.resolve("joularcore-output.csv");
        WorkloadStats workloadStats = null;
        try {
            appRunner.waitForStartup(baseUrl, getHealthCheckPath().get(),
                    getStartupTimeoutSeconds().get());

            File vmPowerFile = getVmPowerFilePath().isPresent()
                    ? getVmPowerFilePath().get().getAsFile()
                    : null;

            JoularCoreConfig joularCoreConfig = new JoularCoreConfig()
                    .binaryPath(joularCoreBinary)
                    .pid(appProcess.pid())
                    .component(getJoularCoreComponent().get())
                    .outputCsvPath(outputCsv)
                    .silent(true)
                    .vmMode(getVmMode().get())
                    .vmPowerFilePath(vmPowerFile != null ? vmPowerFile.toPath() : null);

            JoularCoreRunner joularCoreRunner = new JoularCoreRunner();
            getLogger().lifecycle("Starting Joular Core (monitoring PID " + appProcess.pid() + ")");
            joularCoreRunner.start(joularCoreConfig);

            try {
                if (warmup > 0) {
                    getLogger().lifecycle("Warmup: " + warmup + " s …");
                    runTraining(baseUrl, warmup, 0);
                    joularCoreRunner.stop();
                    Files.deleteIfExists(outputCsv);
                    joularCoreRunner.start(joularCoreConfig);
                }

                getLogger().lifecycle("Measuring energy: " + measure + " s …");
                workloadStats = runTraining(baseUrl, 0, measure);

            } finally {
                joularCoreRunner.stop();
            }

        } finally {
            appRunner.stop(appProcess);
        }
        String appId = springBootJarFile.getName().replace(".jar", "");
        JoularCoreResultReader resultReader = new JoularCoreResultReader();
        EnergyReport report = resultReader.readResults(outputCsv, buildRunId(), measure, appId);

        BaselineManager baselineManager = new BaselineManager();
        Optional<EnergyBaseline> baseline = loadBaseline(baselineManager);
        ComparisonResult comparison = new EnergyComparator().compare(report, baseline,
                getThreshold().get());

        new ConsoleReporter().report(report, comparison, workloadStats, resolvePowerSource());
        Path htmlReport = new HtmlReporter().generateReport(report, comparison, workloadStats, resolvePowerSource(), reportDir);
        getLogger().lifecycle("HTML report: " + htmlReport);

        if (getFailOnRegression().get() && comparison.isFailed()) {
            throw new GradleException(String.format(
                    "Energy regression: %.2f%% increase exceeds threshold %.1f%%. Report: %s",
                    comparison.totalDeltaPercent(), getThreshold().get(), htmlReport));
        }
    }

    private WorkloadStats runTraining(String baseUrl, int warmupSec, int measureSec) throws Exception {
        TrainingConfig config = new TrainingConfig()
                .baseUrl(baseUrl)
                .requestsPerSecond(getRequestsPerSecond().get())
                .warmupDurationSeconds(warmupSec)
                .measureDurationSeconds(measureSec);

        if (getExternalTrainingScriptFile().isPresent()) {
            File scriptFile = getExternalTrainingScriptFile().get().getAsFile();
            if (scriptFile.exists()) {
                config.externalScriptFile(scriptFile.getAbsolutePath());
            }
        } else {
            String extCmd = getExternalTrainingCommand().getOrNull();
            if (extCmd != null && !extCmd.isBlank()) {
                config.externalCommand(extCmd);
            }
        }

        if (!getTrainingPaths().get().isEmpty()) {
            config.paths(getTrainingPaths().get());
        }

        return new TrainingRunner().run(config);
    }

    private Path resolveJoularCoreBinary() throws Exception {
        RegularFileProperty binaryProp = getJoularCoreBinaryPath();
        if (binaryProp.isPresent()) {
            File f = binaryProp.get().getAsFile();
            if (f.exists()) {
                getLogger().lifecycle("Using Joular Core binary: " + f);
                return f.toPath();
            }
        }
        String version = getJoularCoreVersion().get();
        getLogger().lifecycle("Auto-downloading Joular Core " + version + " …");
        return new JoularCoreDownloader().download(version, JoularCoreDownloader.defaultCacheDir());
    }

    private Optional<EnergyBaseline> loadBaseline(BaselineManager manager) {
        RegularFileProperty prop = getBaselineFile();
        if (!prop.isPresent())
            return Optional.empty();
        File f = prop.get().getAsFile();
        if (!f.exists())
            return Optional.empty();
        try {
            return manager.loadBaseline(f.toPath());
        } catch (Exception e) {
            getLogger().warn("Could not load baseline: " + e.getMessage());
            return Optional.empty();
        }
    }

    private Path resolveReportDir() {
        if (getReportOutputDir().isPresent()) {
            return getReportOutputDir().get().getAsFile().toPath();
        }
        return getProject().getLayout().getBuildDirectory().getAsFile().get().toPath().resolve("greener-reports");
    }

    private String buildRunId() {
        String sha = System.getenv("GITHUB_SHA");
        return sha != null && !sha.isBlank()
                ? sha.substring(0, Math.min(sha.length(), 8))
                : String.valueOf(System.currentTimeMillis());
    }

    private PowerSource resolvePowerSource() {
        String override = System.getProperty("greener.powerSource");
        if (override != null && !override.isBlank()) {
            return PowerSource.fromString(override);
        }
        String envOverride = System.getenv("POWER_SOURCE");
        if (envOverride != null && !envOverride.isBlank()) {
            return PowerSource.fromString(envOverride);
        }
        return PowerSource.detect(getVmMode().get());
    }
}
