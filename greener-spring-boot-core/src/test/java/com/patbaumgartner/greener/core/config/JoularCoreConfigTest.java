package com.patbaumgartner.greener.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JoularCoreConfigTest {

    @Test
    void buildCommand_withPid_includesPidFlag() {
        JoularCoreConfig config = new JoularCoreConfig()
                .pid(12345L)
                .component("cpu")
                .outputCsvPath(Path.of("/tmp/joularcore.csv"))
                .silent(true);

        List<String> cmd = config.buildCommand(Path.of("/usr/local/bin/joularcore"));

        assertThat(cmd).contains("-p", "12345");
        assertThat(cmd).contains("-c", "cpu");
        assertThat(cmd).contains("-f", "/tmp/joularcore.csv");
        assertThat(cmd).contains("-s");
        assertThat(cmd.get(0)).endsWith("joularcore");
    }

    @Test
    void buildCommand_withAppName_includesAppFlag() {
        JoularCoreConfig config = new JoularCoreConfig()
                .appName("petclinic")
                .component("cpu")
                .outputCsvPath(Path.of("/tmp/out.csv"))
                .silent(false);

        List<String> cmd = config.buildCommand(Path.of("/usr/bin/joularcore"));

        assertThat(cmd).contains("-a", "petclinic");
        assertThat(cmd).doesNotContain("-s");
    }

    @Test
    void buildCommand_pidTakesPrecedenceOverAppName() {
        JoularCoreConfig config = new JoularCoreConfig()
                .pid(99L)
                .appName("petclinic")
                .outputCsvPath(Path.of("/tmp/out.csv"));

        List<String> cmd = config.buildCommand(Path.of("/usr/bin/joularcore"));

        assertThat(cmd).contains("-p", "99");
        assertThat(cmd).doesNotContain("-a");
    }

    @Test
    void buildCommand_extraArgsAppended() {
        JoularCoreConfig config = new JoularCoreConfig()
                .pid(1L)
                .outputCsvPath(Path.of("/tmp/out.csv"))
                .extraArgs(List.of("--cpu-idle-baseline", "5.0"));

        List<String> cmd = config.buildCommand(Path.of("/usr/bin/joularcore"));

        assertThat(cmd).contains("--cpu-idle-baseline", "5.0");
    }

    @Test
    void defaultVersion_isSet() {
        assertThat(new JoularCoreConfig().getVersion())
                .isEqualTo(JoularCoreConfig.DEFAULT_VERSION);
    }

    @Test
    void buildCommand_vmMode_addsVmFlag() {
        JoularCoreConfig config = new JoularCoreConfig()
                .pid(42L)
                .outputCsvPath(Path.of("/tmp/out.csv"))
                .vmMode(true);

        List<String> cmd = config.buildCommand(Path.of("/usr/bin/joularcore"));

        assertThat(cmd).contains("--vm");
        assertThat(cmd).doesNotContain("--vm-power-file");
    }

    @Test
    void buildCommand_vmModeWithPowerFile_addsBothFlags(@TempDir Path tmp) throws IOException {
        Path powerFile = tmp.resolve("vm-power.txt");
        Files.writeString(powerFile, "45.5");

        JoularCoreConfig config = new JoularCoreConfig()
                .pid(42L)
                .outputCsvPath(Path.of("/tmp/out.csv"))
                .vmMode(true)
                .vmPowerFilePath(powerFile);

        List<String> cmd = config.buildCommand(Path.of("/usr/bin/joularcore"));

        assertThat(cmd).contains("--vm");
        assertThat(cmd).contains("--vm-power-file", powerFile.toAbsolutePath().toString());
    }

    @Test
    void buildCommand_vmModeOff_doesNotAddVmFlag() {
        JoularCoreConfig config = new JoularCoreConfig()
                .pid(1L)
                .outputCsvPath(Path.of("/tmp/out.csv"))
                .vmMode(false);

        List<String> cmd = config.buildCommand(Path.of("/usr/bin/joularcore"));

        assertThat(cmd).doesNotContain("--vm");
        assertThat(cmd).doesNotContain("--vm-power-file");
    }

    @Test
    void vmModeGetters_reflectSetValues(@TempDir Path tmp) throws IOException {
        Path powerFile = tmp.resolve("power.txt");
        Files.createFile(powerFile);

        JoularCoreConfig config = new JoularCoreConfig()
                .vmMode(true)
                .vmPowerFilePath(powerFile);

        assertThat(config.isVmMode()).isTrue();
        assertThat(config.getVmPowerFilePath()).isEqualTo(powerFile);
    }
}
