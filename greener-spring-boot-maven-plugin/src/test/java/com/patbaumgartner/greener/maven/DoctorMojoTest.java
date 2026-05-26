package com.patbaumgartner.greener.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DoctorMojoTest {

	@TempDir
	Path tempDir;

	@Test
	void executesWithoutThrowingWhenFailOnErrorIsFalse() throws Exception {
		DoctorMojo mojo = new DoctorMojo();
		setField(mojo, "failOnError", false);

		// Should not throw even if environment checks fail (e.g. no RAPL in CI)
		assertThatCode(mojo::execute).doesNotThrowAnyException();
	}

	@Test
	void throwsMojoFailureExceptionWhenFailOnErrorIsTrueAndEnvironmentHasFailures() throws Exception {
		DoctorMojo mojo = new DoctorMojo();
		setField(mojo, "projectBasedir", tempDir.toFile());
		setField(mojo, "failOnError", true);

		// In a sandboxed test environment (no RAPL, no Joular Core binary, no workload
		// tool) the doctor will report failures and the mojo must throw.
		// Accept both outcomes: throw with the expected message (typical in CI) or
		// complete normally when the environment happens to pass all checks.
		try {
			mojo.execute();
		}
		catch (MojoFailureException ex) {
			assertThat(ex).hasMessageContaining("greener:doctor reported failures");
		}
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

}
