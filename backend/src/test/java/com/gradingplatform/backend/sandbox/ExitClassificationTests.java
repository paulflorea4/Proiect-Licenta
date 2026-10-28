package com.gradingplatform.backend.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gradingplatform.backend.sandbox.ExitClassification.ContainerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 4.5a: the decision table, without Docker; 7.7b: a container that did not run is never a grade. */
class ExitClassificationTests {

    /** What Docker recorded for a run command missing from the image (seen with the real sandbox image). */
    private static final String START_FAILURE = "failed to create task for container: failed to create shim task:"
            + " OCI runtime create failed: runc create failed: unable to start container process: error during"
            + " container init: exec: \"no-such-command\": executable file not found in $PATH";

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
        "128, false, RUNTIME_ERROR",
        "137, false, MEMORY_LIMIT", // the kernel's SIGKILL whose flag Docker did not record (seen on CI)
        "139, false, RUNTIME_ERROR", // segmentation fault
        "137, true,  MEMORY_LIMIT",
        "1,   true,  MEMORY_LIMIT", // a program that exits 1 after the kernel killed its worker
        "255, true,  MEMORY_LIMIT"
    })
    void theTable(int exitCode, boolean oomKilled, SandboxStatus expected) {
        assertThat(ExitClassification.classify(new ContainerState("exited", exitCode, oomKilled, "")))
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}, exit {1} -> SANDBOX_ERROR")
    @CsvSource({
        "created, 127", // the run command is not in the image
        "created, 126", // it is there but cannot be executed
        "created, 128", // the runtime failed another way
        "created, 0", // never started, no code recorded
        "running, 0", // still running after `docker run` returned
        "dead,    137",
        "paused,  0",
        "restarting, 1",
        "removing, 0"
    })
    void aContainerThatDidNotExitOnItsOwnIsASandboxErrorWhateverTheCode(String status, int exitCode) {
        assertThat(ExitClassification.classify(new ContainerState(status, exitCode, false, "")))
                .isEqualTo(SandboxStatus.SANDBOX_ERROR);
        assertThat(ExitClassification.classify(new ContainerState(status, exitCode, true, START_FAILURE)))
                .isEqualTo(SandboxStatus.SANDBOX_ERROR);
    }

    @ParameterizedTest(name = "exit {0} with an error -> SANDBOX_ERROR")
    @ValueSource(ints = {0, 1, 127, 137})
    void anExitedContainerWithAnErrorRecordedIsASandboxError(int exitCode) {
        assertThat(ExitClassification.classify(new ContainerState("exited", exitCode, false, START_FAILURE)))
                .isEqualTo(SandboxStatus.SANDBOX_ERROR);
    }

    @Test
    void theInspectOutputIsParsed() {
        assertThat(ExitClassification.parse("exited 137 true \n"))
                .contains(new ContainerState("exited", 137, true, ""));
        assertThat(ExitClassification.parse("exited 0 false")).contains(new ContainerState("exited", 0, false, ""));
        assertThat(ExitClassification.parse("  exited 1 false \r\n"))
                .contains(new ContainerState("exited", 1, false, ""));
    }

    @Test
    void theErrorIsReadWholeWithItsSpacesAndQuotes() {
        assertThat(ExitClassification.parse("created 127 false " + START_FAILURE + "\n"))
                .contains(new ContainerState("created", 127, false, START_FAILURE));
    }

    @Test
    void theInspectFormatAsksForStatusCodeFlagAndErrorInThatOrder() {
        assertThat(ExitClassification.INSPECT_FORMAT)
                .isEqualTo("{{.State.Status}} {{.State.ExitCode}} {{.State.OOMKilled}} {{.State.Error}}");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "exited",
                "exited 137",
                "137 true", // the format before 7.7b
                "exited abc true",
                "exited 137 maybe",
                "exited 137  true",
                "exited true 137",
                "Exited 0 false",
                "<no value> 0 false",
                "<no value>"
            })
    void anythingElseIsNotAState(String output) {
        assertThat(ExitClassification.parse(output)).isEmpty();
    }
}
