package com.patbaumgartner.greener.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

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
 *     id("com.patbaumgartner.greener-spring-boot") version "0.2.0"
 * }
 *
 * greener {
 *     // springBootJar auto-detected from build/libs/ when omitted
 *     measureDurationSeconds = 60
 *     threshold = 10.0
 *     failOnRegression = false
 * }
 * }</pre>
 */
public class GreenerPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        GreenerExtension extension = project.getExtensions()
                .create("greener", GreenerExtension.class);

        project.getTasks().register("measureEnergy", MeasureEnergyTask.class, task -> {
            task.setGroup("greener");
            task.setDescription(
                    "Measures energy consumption of the Spring Boot application using Joular Core "
                            + "and compares against the stored baseline.");
            task.getSpringBootJar().convention(extension.getSpringBootJar());
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
        });

        project.getTasks().register("updateEnergyBaseline", UpdateBaselineTask.class, task -> {
            task.setGroup("greener");
            task.setDescription(
                    "Promotes the most recent energy measurement as the new baseline for "
                            + "future comparisons.");
            task.getBaselineFile().convention(extension.getBaselineFile());
            task.getReportOutputDir().convention(extension.getReportOutputDir());
        });
    }
}
