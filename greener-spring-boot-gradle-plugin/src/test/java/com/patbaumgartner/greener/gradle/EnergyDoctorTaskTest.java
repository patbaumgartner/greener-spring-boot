package com.patbaumgartner.greener.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyDoctorTaskTest {

	private static EnergyDoctorTask doctorTaskOf(Project project) {
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");
		return (EnergyDoctorTask) project.getTasks().getByName("energyDoctor");
	}

	@Test
	void pluginRegistersTheDoctorTask() {
		Project project = ProjectBuilder.builder().build();

		assertThat(doctorTaskOf(project)).isNotNull();
	}

	@Test
	void externalTrainingCommandFromTheExtensionReachesTheTask() {
		Project project = ProjectBuilder.builder().build();
		EnergyDoctorTask task = doctorTaskOf(project);
		GreenerExtension extension = project.getExtensions().getByType(GreenerExtension.class);

		extension.getExternalTrainingCommand().set("oha -n 500 -c 10 http://localhost:8080/actuator/health");

		assertThat(task.getExternalTrainingCommand().get())
			.isEqualTo("oha -n 500 -c 10 http://localhost:8080/actuator/health");
	}

	@Test
	void doctorRunsAndReportsTheWorkloadToolDerivedFromTheTrainingCommand() {
		Project project = ProjectBuilder.builder().build();
		EnergyDoctorTask task = doctorTaskOf(project);
		project.getExtensions()
			.getByType(GreenerExtension.class)
			.getExternalTrainingCommand()
			.set("definitely-not-on-path -n 1 http://localhost:8080");
		task.getFailOnError().set(false);

		task.runDoctor();

		assertThat(task.getExternalTrainingCommand().get()).startsWith("definitely-not-on-path");
	}

	@Test
	void failOnErrorDefaultsToTrue() {
		Project project = ProjectBuilder.builder().build();

		assertThat(doctorTaskOf(project).getFailOnError().get()).isTrue();
	}

	@Test
	void workloadCommandIsUnsetByDefault() {
		Project project = ProjectBuilder.builder().build();

		assertThat(doctorTaskOf(project).getWorkloadCommand().getOrNull()).isNull();
	}

}
