package com.patbaumgartner.greener.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(ext.getApplicationPort().get()).isEqualTo(8080);
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

        assertThat(task.getApplicationPort().get()).isEqualTo(8080);
        assertThat(task.getJoularCoreComponent().get()).isEqualTo("cpu");
        assertThat(task.getVmMode().get()).isFalse();
        assertThat(task.getMeasureDurationSeconds().get()).isEqualTo(60);
        assertThat(task.getThreshold().get()).isEqualTo(10.0);
        assertThat(task.getFailOnRegression().get()).isFalse();
    }

}
