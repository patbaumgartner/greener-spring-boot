package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.comparator.EnergyComparator;
import com.patbaumgartner.greener.core.config.JoularCoreConfig;
import com.patbaumgartner.greener.core.config.PluginDefaults;
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
import org.gradle.api.file.DirectoryProperty;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
     * Path to the executable Spring Boot fat-jar. When omitted the task
     * auto-detects a single jar in {@code build/libs/}.
     * 
     * @return the Spring Boot jar property
     */
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    @org.gradle.api.tasks.Optional
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
     * Joular Core release version to download when
     * {@link #getJoularCoreBinaryPath()} is not set. See
     * <a href="https://github.com/joular/joularcore/releases">Joular Core
     * releases</a>
     * for available versions.
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
     * Base URL of the Spring Boot application used by the built-in HTTP loader.
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
     * Number of HTTP requests per second issued by the built-in HTTP loader
     * during the training run.
     * 
     * @return the requests per second property
     */
    @Input
    public abstract Property<Integer> getRequestsPerSecond();

    /**
     * Optional external command used as the training workload instead of the
     * built-in HTTP loader (e.g. {@code k6 run script.js}). The {@code APP_URL}
     * environment variable is set to {@link #getBaseUrl()}.
     * 
     * @return the external training command property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getExternalTrainingCommand();

    /**
     * Path to an external shell script file used as the training workload.
     * Takes precedence over {@link #getExternalTrainingCommand()} when set.
     *
     * <p>
     * Available environment variables in the script: {@code APP_URL},
     * {@code APP_HOST}, {@code APP_PORT}, {@code WARMUP_SECONDS},
     * {@code MEASURE_SECONDS}, {@code TOTAL_SECONDS}, {@code RPS}.
     *
     * <p>
     * See {@code examples/workloads/} for wrk, wrk2, oha, and Gatling examples.
     * 
     * @return the external training script file property
     */
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getExternalTrainingScriptFile();

    /**
     * Enable Joular Core VM mode.
     *
     * <p>
     * In VM mode Joular Core cannot read RAPL counters directly and instead
     * reads the VM's total power from a file written by the host — see
     * {@link #getVmPowerFilePath()}. The host must write the VM's instantaneous
     * power (Watts) to that file once per second.
     * 
     * @return the VM mode property
     */
    @Input
    public abstract Property<Boolean> getVmMode();

    /**
     * Path to the VM power file that Joular Core reads when {@link #getVmMode()}
     * is {@code true}. The file must contain a single floating-point number
     * (e.g. {@code 45.2}).
     * 
     * @return the VM power file path property
     */
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getVmPowerFilePath();

    /**
     * Warmup duration in seconds. The application runs under load during warmup
     * so the JIT compiler warms up, but energy data from this phase is discarded.
     * 
     * @return the warmup duration property
     */
    @Input
    public abstract Property<Integer> getWarmupDurationSeconds();

    /**
     * Duration in seconds of the actual measurement window after warmup.
     * 
     * @return the measure duration property
     */
    @Input
    public abstract Property<Integer> getMeasureDurationSeconds();

    /**
     * Seconds to wait for the application health endpoint before aborting.
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
     * Maximum allowed percentage increase in total energy before the build is
     * failed. For example {@code 10} means a 10 % regression is tolerated.
     * 
     * @return the threshold property
     */
    @Input
    public abstract Property<Double> getThreshold();

    /**
     * When {@code true}, the build is failed if energy consumption regressed
     * beyond {@link #getThreshold()}.
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
    public abstract DirectoryProperty getReportOutputDir();

    /**
     * Runs the energy measurement workflow.
     * 
     * @throws Exception if measurement fails
     */
    @TaskAction
    public void measureEnergy() throws Exception {
        File springBootJarFile;
        if (getSpringBootJar().isPresent()) {
            springBootJarFile = getSpringBootJar().get().getAsFile();
        } else {
            springBootJarFile = autoDetectSpringBootJar();
        }
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
        List<String> effectiveAppArgs = PluginDefaults.buildEffectiveAppArgs(null);

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
                    getLogger().lifecycle("Warmup: " + warmup + " s ...");
                    runTraining(baseUrl, warmup, 0);
                    joularCoreRunner.stop();
                    Files.deleteIfExists(outputCsv);
                    joularCoreRunner.start(joularCoreConfig);
                }

                getLogger().lifecycle("Measuring energy: " + measure + " s ...");
                workloadStats = runTraining(baseUrl, 0, measure);

            } finally {
                joularCoreRunner.stop();
            }

        } finally {
            appRunner.stop(appProcess);
        }
        String appId = springBootJarFile.getName().replace(".jar", "");
        JoularCoreResultReader resultReader = new JoularCoreResultReader();
        EnergyReport report = resultReader.readResults(outputCsv, PluginDefaults.buildRunId(), measure, appId);

        BaselineManager baselineManager = new BaselineManager();
        Optional<EnergyBaseline> baseline = loadBaseline(baselineManager);
        ComparisonResult comparison = new EnergyComparator().compare(report, baseline,
                getThreshold().get());

        PowerSource powerSource = PluginDefaults.resolvePowerSource(getVmMode().get());
        new ConsoleReporter().report(report, comparison, workloadStats, powerSource);
        Path htmlReport = new HtmlReporter().generateReport(report, comparison, workloadStats, powerSource,
                reportDir);
        getLogger().lifecycle("HTML report: " + htmlReport);

        // Save latest report so that updateEnergyBaseline can promote it
        Path latestReportPath = reportDir.resolve("latest-energy-report.json");
        baselineManager.saveBaseline(report, null, null, latestReportPath);
        getLogger().lifecycle("Latest energy report saved to: " + latestReportPath);

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
        getLogger().lifecycle("Auto-downloading Joular Core " + version + " ...");
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

    /**
     * Auto-detects the Spring Boot fat-jar in {@code build/libs/}. Looks for a
     * single executable jar, excluding sources, javadoc, and test jars.
     */
    private File autoDetectSpringBootJar() {
        Path libsDir = getProject().getLayout().getBuildDirectory().getAsFile().get().toPath()
                .resolve("libs");

        try {
            Optional<File> jar = PluginDefaults.autoDetectJar(libsDir, "-plain.jar");
            if (jar.isEmpty()) {
                throw new GradleException("springBootJar not configured and build/libs/ not found: "
                        + libsDir + ". Set springBootJar explicitly or run 'bootJar' first.");
            }
            getLogger().lifecycle("Auto-detected Spring Boot jar: " + jar.get());
            return jar.get();
        } catch (IllegalStateException e) {
            throw new GradleException(
                    e.getMessage() + ". Run 'bootJar' first or set springBootJar explicitly.");
        }
    }

}
