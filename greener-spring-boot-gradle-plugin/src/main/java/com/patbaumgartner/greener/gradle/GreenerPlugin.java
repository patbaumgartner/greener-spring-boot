package com.patbaumgartner.greener.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.ProviderFactory;

/**
 * Gradle plugin entry point for <strong>greener-spring-boot</strong>.
 *
 * <p>
 * Registers the {@link GreenerExtension} DSL block and the two tasks:
 * <ul>
 * <li>{@code measureEnergy} — runs the Spring Boot application under
 * <a href="https://www.noureddine.org/research/joular/joularcore">Joular
 * Core</a>
 * and compares the measured energy against a baseline.</li>
 * <li>{@code updateEnergyBaseline} — promotes the most recent measurement
 * result as the new baseline.</li>
 * </ul>
 *
 * <h2>Minimal {@code build.gradle.kts} configuration</h2>
 * 
 * <pre>{@code
 * plugins {
 *     id("com.patbaumgartner.greener-spring-boot") version "<version>"
 * }
 *
 * greener {
 *     // springBootJar auto-detected from build/libs/ when omitted
 *     measureDurationSeconds.set(60)
 *     threshold.set(10.0)
 *     failOnRegression.set(false)
 * }
 * }</pre>
 */
public class GreenerPlugin implements Plugin<Project> {

    private static final String EXTENSION_NAME = "greener";
    private static final String TASK_GROUP = "greener";
    private static final String MEASURE_ENERGY_TASK_NAME = "measureEnergy";
    private static final String UPDATE_BASELINE_TASK_NAME = "updateEnergyBaseline";

    @Override
    public void apply(Project project) {
        GreenerExtension extension = project.getExtensions().create(EXTENSION_NAME, GreenerExtension.class);

        // Apply useful project-level conventions down to the extension so tasks don't
        // need getProject()
        extension.getBaselineFile().convention(project.getLayout().getProjectDirectory().file("energy-baseline.json"));
        extension.getReportOutputDir().convention(project.getLayout().getBuildDirectory().dir("greener-reports"));

        // Default commitSha/branch from environment variables (same as Maven's ${env.GITHUB_SHA})
        ProviderFactory providers = project.getProviders();
        extension.getCommitSha().convention(providers.environmentVariable("GITHUB_SHA"));
        extension.getBranch().convention(providers.environmentVariable("GITHUB_REF_NAME"));

        configureMeasureEnergyTask(project, extension);
        configureUpdateBaselineTask(project, extension);
    }

    private void configureMeasureEnergyTask(Project project, GreenerExtension extension) {
        project.getTasks().register(MEASURE_ENERGY_TASK_NAME, MeasureEnergyTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription(
                    "Measures energy consumption of the Spring Boot application using Joular Core "
                            + "and compares against the stored baseline.");
            task.getSpringBootJar().convention(extension.getSpringBootJar());
            task.getJvmArgs().convention(extension.getJvmArgs());
            task.getAppArgs().convention(extension.getAppArgs());
            task.getJoularCoreBinaryPath().convention(extension.getJoularCoreBinaryPath());
            task.getJoularCoreVersion().convention(extension.getJoularCoreVersion());
            task.getJoularCoreComponent().convention(extension.getJoularCoreComponent());
            task.getBaseUrl().convention(extension.getBaseUrl());
            task.getRequestsPerSecond().convention(extension.getRequestsPerSecond());
            task.getExternalTrainingCommand().convention(extension.getExternalTrainingCommand());
            task.getExternalTrainingScriptFile().convention(extension.getExternalTrainingScriptFile());
            task.getVmMode().convention(extension.getVmMode());
            task.getVmPowerFilePath().convention(extension.getVmPowerFilePath());
            task.getWarmupDurationSeconds().convention(extension.getWarmupDurationSeconds());
            task.getMeasureDurationSeconds().convention(extension.getMeasureDurationSeconds());
            task.getStartupTimeoutSeconds().convention(extension.getStartupTimeoutSeconds());
            task.getHealthCheckPath().convention(extension.getHealthCheckPath());
            task.getBaselineFile().convention(extension.getBaselineFile());
            task.getThreshold().convention(extension.getThreshold());
            task.getFailOnRegression().convention(extension.getFailOnRegression());
            task.getReportOutputDir().convention(extension.getReportOutputDir());
            task.getAutoUpdateBaseline().convention(extension.getAutoUpdateBaseline());
            task.getCommitSha().convention(extension.getCommitSha());
            task.getBranch().convention(extension.getBranch());
            task.getTimestampReports().convention(extension.getTimestampReports());
            task.getSkip().convention(extension.getSkip());
        });
    }

    private void configureUpdateBaselineTask(Project project, GreenerExtension extension) {
        project.getTasks().register(UPDATE_BASELINE_TASK_NAME, UpdateBaselineTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription(
                    "Promotes the most recent energy measurement as the new baseline for "
                            + "future comparisons.");
            task.getBaselineFile().convention(extension.getBaselineFile());
            task.getLatestReportFile().convention(extension.getLatestReportFile());
            task.getReportOutputDir().convention(extension.getReportOutputDir());
            task.getCommitSha().convention(extension.getCommitSha());
            task.getBranch().convention(extension.getBranch());
            task.getSkip().convention(extension.getSkip());
        });
    }
}
