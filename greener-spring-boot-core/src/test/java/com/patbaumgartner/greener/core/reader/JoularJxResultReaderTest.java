package com.patbaumgartner.greener.core.reader;

import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JoularJxResultReaderTest {

    private final JoularJxResultReader reader = new JoularJxResultReader();

    @Test
    void parseCsv_twoColumnFormat(@TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("total.csv");
        Files.writeString(csv, """
                org.example.Service.doWork,42.5
                org.example.Controller.handleRequest,17.3
                org.example.Repository.findAll,5.0
                """);

        List<EnergyMeasurement> measurements = reader.parseCsv(csv);

        assertThat(measurements).hasSize(3);
        assertThat(measurements).extracting(EnergyMeasurement::methodName)
                .containsExactly(
                        "org.example.Service.doWork",
                        "org.example.Controller.handleRequest",
                        "org.example.Repository.findAll");
        assertThat(measurements).extracting(EnergyMeasurement::energyJoules)
                .containsExactly(42.5, 17.3, 5.0);
    }

    @Test
    void parseCsv_skipsBlankAndCommentLines(@TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("total.csv");
        Files.writeString(csv, """
                # This is a comment
                org.example.Service.doWork,10.0
                
                org.example.Other.method,5.0
                """);

        assertThat(reader.parseCsv(csv)).hasSize(2);
    }

    @Test
    void parseCsv_skipsNonNumericLines(@TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("total.csv");
        Files.writeString(csv, """
                methodName,energyJoules
                org.example.Service.doWork,10.0
                """);

        List<EnergyMeasurement> measurements = reader.parseCsv(csv);
        assertThat(measurements).hasSize(1);
        assertThat(measurements.get(0).methodName()).isEqualTo("org.example.Service.doWork");
    }

    @Test
    void readResults_nonExistentDirectory_returnsEmptyReport(@TempDir Path tmp) throws IOException {
        EnergyReport report = reader.readResults(tmp.resolve("missing"), "run-1", 60L);

        assertThat(report.measurements()).isEmpty();
        assertThat(report.totalEnergyJoules()).isEqualTo(0.0);
    }

    @Test
    void readResults_appTotalMethodsCsv_parsedCorrectly(@TempDir Path tmp) throws IOException {
        Path methodsDir = tmp.resolve("myapp-123-0/app/total/methods");
        Files.createDirectories(methodsDir);
        Files.writeString(methodsDir.resolve("ts.csv"), """
                org.example.Service.fetchData,100.0
                org.example.Service.processData,50.0
                """);

        EnergyReport report = reader.readResults(tmp, "run-1", 60L);

        assertThat(report.measurements()).hasSize(2);
        assertThat(report.totalEnergyJoules()).isEqualTo(150.0);
    }

    @Test
    void readResults_fallsBackToAllMethods_whenAppDirMissing(@TempDir Path tmp) throws IOException {
        Path methodsDir = tmp.resolve("myapp-123-0/all/total/methods");
        Files.createDirectories(methodsDir);
        Files.writeString(methodsDir.resolve("ts.csv"), "java.lang.Object.toString,1.0\n");

        EnergyReport report = reader.readResults(tmp, "run-1", 60L);

        assertThat(report.measurements()).hasSize(1);
    }
}
