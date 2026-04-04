package com.patbaumgartner.greener.gradle;

import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GreenerPluginTest {

    @Test
    void pluginRegistersExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        assertThat(project.getExtensions().findByName("greener"))
                .isNotNull()
                .isInstanceOf(GreenerExtension.class);
    }

    @Test
    void pluginRegistersMeasureEnergyTask() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        assertThat(project.getTasks().findByName("measureEnergy"))
                .isNotNull()
                .isInstanceOf(MeasureEnergyTask.class);
    }

    @Test
    void pluginRegistersUpdateBaselineTask() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        assertThat(project.getTasks().findByName("updateEnergyBaseline"))
                .isNotNull()
                .isInstanceOf(UpdateBaselineTask.class);
    }

    @Test
    void tasksBelongToGreenerGroup() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        assertThat(project.getTasks().getByName("measureEnergy").getGroup()).isEqualTo("greener");
        assertThat(project.getTasks().getByName("updateEnergyBaseline").getGroup()).isEqualTo("greener");
    }

    @Test
    void extensionDefaultsAreSet() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);

        assertThat(ext.getJoularCoreComponent().get()).isEqualTo("cpu");
        assertThat(ext.getVmMode().get()).isFalse();
        assertThat(ext.getBaseUrl().get()).isEqualTo("http://localhost:8080");
        assertThat(ext.getRequestsPerSecond().get()).isEqualTo(5);
        assertThat(ext.getWarmupDurationSeconds().get()).isEqualTo(30);
        assertThat(ext.getMeasureDurationSeconds().get()).isEqualTo(60);
        assertThat(ext.getStartupTimeoutSeconds().get()).isEqualTo(120);
        assertThat(ext.getHealthCheckPath().get()).isEqualTo("/actuator/health/readiness");
        assertThat(ext.getThreshold().get()).isEqualTo(10.0);
        assertThat(ext.getFailOnRegression().get()).isFalse();
    }

    @Test
    void measureTaskConventionsFromExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");

        assertThat(task.getJoularCoreComponent().get()).isEqualTo("cpu");
        assertThat(task.getVmMode().get()).isFalse();
        assertThat(task.getMeasureDurationSeconds().get()).isEqualTo(60);
        assertThat(task.getThreshold().get()).isEqualTo(10.0);
        assertThat(task.getFailOnRegression().get()).isFalse();
    }

    // ---- H4: Task-level tests ----

    @Test
    void vmModeConventionPropagatesFromExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
        ext.getVmMode().set(true);

        MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
        assertThat(task.getVmMode().get()).isTrue();
    }

    @Test
    void vmPowerFilePathConventionPropagatesFromExtension(@TempDir Path tempDir) throws IOException {
        Path vmFile = tempDir.resolve("vm-power.txt");
        Files.writeString(vmFile, "42.5");

        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
        ext.getVmPowerFilePath().set(vmFile.toFile());

        MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
        assertThat(task.getVmPowerFilePath().get().getAsFile()).isEqualTo(vmFile.toFile());
    }

    @Test
    void extensionOverridesPropagateThroughConventions() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
        ext.getBaseUrl().set("http://localhost:9090");
        ext.getRequestsPerSecond().set(100);
        ext.getWarmupDurationSeconds().set(10);
        ext.getMeasureDurationSeconds().set(120);
        ext.getStartupTimeoutSeconds().set(300);
        ext.getHealthCheckPath().set("/health/live");
        ext.getThreshold().set(5.0);
        ext.getFailOnRegression().set(true);

        MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");

        assertThat(task.getBaseUrl().get()).isEqualTo("http://localhost:9090");
        assertThat(task.getRequestsPerSecond().get()).isEqualTo(100);
        assertThat(task.getWarmupDurationSeconds().get()).isEqualTo(10);
        assertThat(task.getMeasureDurationSeconds().get()).isEqualTo(120);
        assertThat(task.getStartupTimeoutSeconds().get()).isEqualTo(300);
        assertThat(task.getHealthCheckPath().get()).isEqualTo("/health/live");
        assertThat(task.getThreshold().get()).isEqualTo(5.0);
        assertThat(task.getFailOnRegression().get()).isTrue();
    }

    @Test
    void updateBaselineTaskConventionsFromExtension(@TempDir Path tempDir) throws IOException {
        Path baseline = tempDir.resolve("energy-baseline.json");
        Files.writeString(baseline, "{}");

        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
        ext.getBaselineFile().set(baseline.toFile());

        UpdateBaselineTask task = (UpdateBaselineTask) project.getTasks().getByName("updateEnergyBaseline");
        assertThat(task.getBaselineFile().get().getAsFile()).isEqualTo(baseline.toFile());
    }

    @Test
    void externalTrainingCommandPropagatesFromExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
        ext.getExternalTrainingCommand().set("oha -n 1000 http://localhost:8080/");

        MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
        assertThat(task.getExternalTrainingCommand().get()).isEqualTo("oha -n 1000 http://localhost:8080/");
    }

    @Test
    void externalTrainingScriptFilePropagatesFromExtension(@TempDir Path tempDir) throws IOException {
        Path script = tempDir.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\necho test");

        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

        GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
        ext.getExternalTrainingScriptFile().set(script.toFile());

        MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
        assertThat(task.getExternalTrainingScriptFile().get().getAsFile()).isEqualTo(script.toFile());
    }

}
