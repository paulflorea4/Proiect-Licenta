package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.sandbox.ExitClassification.ContainerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 4.5a: the decision table, without Docker. */
class ExitClassificationTests {

    @ParameterizedTest(name = "exit {0}, oom {1} -> {2}")
    @CsvSource({
        "0,   false, OK",
        "0,   true,  OK", // the main process finished by itself; a killed helper does not undo that
        "1,   false, RUNTIME_ERROR",
        "2,   false, RUNTIME_ERROR",
        "3,   false, RUNTIME_ERROR",
        "125, false, RUNTIME_ERROR", // a program's own code, not docker's failure
        "126, false, RUNTIME_ERROR",
        "127, false, RUNTIME_ERROR",
        "137, false, MEMORY_LIMIT", // the kernel's SIGKILL whose flag Docker did not record (seen on CI)
        "139, false, RUNTIME_ERROR", // segmentation fault
        "137, true,  MEMORY_LIMIT",
        "1,   true,  MEMORY_LIMIT", // a program that exits 1 after the kernel killed its worker
        "255, true,  MEMORY_LIMIT"
    })
    void theTable(int exitCode, boolean oomKilled, SandboxStatus expected) {
        assertThat(ExitClassification.classify(new ContainerState(exitCode, oomKilled)))
                .isEqualTo(expected);
    }

    @Test
    void theInspectOutputIsParsed() {
        assertThat(ExitClassification.parse("137 true\n")).contains(new ContainerState(137, true));
        assertThat(ExitClassification.parse("0 false")).contains(new ContainerState(0, false));
        assertThat(ExitClassification.parse("  1 false \r\n")).contains(new ContainerState(1, false));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "137", "137 true extra", "abc true", "137 maybe", "137  true", "true 137", "<no value>"})
    void anythingElseIsNotAState(String output) {
        assertThat(ExitClassification.parse(output)).isEmpty();
    }
}
