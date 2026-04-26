package com.patbaumgartner.greener.core.exception;

import com.patbaumgartner.greener.core.exception.EnergyMeasurementException.Hint;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyMeasurementExceptionTest {

	@Test
	void messageIncludesHintGuidance() {
		EnergyMeasurementException ex = new EnergyMeasurementException(Hint.EMPTY_OR_MISSING_CSV, "csv was empty");

		assertThat(ex.getMessage()).contains("csv was empty")
			.contains("EMPTY_OR_MISSING_CSV")
			.contains("greener:doctor");
		assertThat(ex.hint()).isEqualTo(Hint.EMPTY_OR_MISSING_CSV);
	}

	@Test
	void preservesCauseAndIsIoException() {
		IOException cause = new IOException("boom");
		EnergyMeasurementException ex = new EnergyMeasurementException(Hint.GENERIC_IO, "wrapper", cause);

		assertThat(ex).isInstanceOf(IOException.class);
		assertThat(ex.getCause()).isSameAs(cause);
	}

	@Test
	void everyHintHasNonBlankGuidance() {
		for (Hint h : Hint.values()) {
			assertThat(h.guidance()).as("hint %s guidance", h).isNotBlank();
		}
	}

}
