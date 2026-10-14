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
        "137, false, RUNTIME_ERROR", // SIGKILL that was not the memory killer, or a plain exit(137)
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

    // --- reading a 137 again (the flag can arrive a moment after the exit) -------------------------

    /** Hands out the given reads one by one (the last one repeats) and counts reads and pauses. */
    private static final class Script {
        private final java.util.List<java.util.Optional<ContainerState>> reads;
        int inspects;
        int pauses;

        Script(java.util.Optional<ContainerState>... reads) {
            this.reads = java.util.List.of(reads);
        }

        java.util.Optional<ContainerState> inspect() {
            return reads.get(Math.min(inspects++, reads.size() - 1));
        }

        boolean pause() {
            pauses++;
            return true;
        }
    }

    @SafeVarargs
    private static Script script(ContainerState... states) {
        return new Script(java.util.Arrays.stream(states)
                .map(java.util.Optional::ofNullable)
                .toArray(java.util.Optional[]::new));
    }

    @ParameterizedTest(name = "exit {0}, oom {1}: final at once")
    @CsvSource({"0,false", "1,false", "139,false", "137,true", "1,true"})
    void everyStateExceptA137WithoutTheFlagIsFinalAtOnce(int exitCode, boolean oomKilled) {
        Script script = script(new ContainerState(exitCode, oomKilled));

        var state = ExitClassification.settle(script::inspect, 5, script::pause);

        assertThat(state).contains(new ContainerState(exitCode, oomKilled));
        assertThat(script.inspects).isEqualTo(1);
        assertThat(script.pauses).isZero();
    }

    @Test
    void aFlagThatArrivesLateIsPickedUp() {
        Script script =
                script(new ContainerState(137, false), new ContainerState(137, false), new ContainerState(137, true));

        var state = ExitClassification.settle(script::inspect, 5, script::pause);

        assertThat(state).contains(new ContainerState(137, true));
        assertThat(ExitClassification.classify(state.orElseThrow())).isEqualTo(SandboxStatus.MEMORY_LIMIT);
        assertThat(script.inspects).isEqualTo(3);
        assertThat(script.pauses).isEqualTo(2);
    }

    @Test
    void aGenuine137WithoutTheFlagIsBelievedAfterTheRechecksAreUsedUp() {
        Script script = script(new ContainerState(137, false));

        var state = ExitClassification.settle(script::inspect, 5, script::pause);

        assertThat(state).contains(new ContainerState(137, false));
        assertThat(ExitClassification.classify(state.orElseThrow())).isEqualTo(SandboxStatus.RUNTIME_ERROR);
        assertThat(script.inspects).as("the first read and five more").isEqualTo(6);
        assertThat(script.pauses).isEqualTo(5);
    }

    @Test
    void noRechecksMeansTheFirstReadStands() {
        Script script = script(new ContainerState(137, false));

        var state = ExitClassification.settle(script::inspect, 0, script::pause);

        assertThat(state).contains(new ContainerState(137, false));
        assertThat(script.inspects).isEqualTo(1);
    }

    @Test
    void aStateThatCannotBeReadIsNotReadAgain() {
        Script script = new Script(java.util.Optional.empty());

        var state = ExitClassification.settle(script::inspect, 5, script::pause);

        assertThat(state).isEmpty();
        assertThat(script.inspects).isEqualTo(1);
        assertThat(script.pauses).isZero();
    }

    @Test
    void aFailedRecheckKeepsTheLastGoodState() {
        Script script = new Script(java.util.Optional.of(new ContainerState(137, false)), java.util.Optional.empty());

        var state = ExitClassification.settle(script::inspect, 3, script::pause);

        assertThat(state).contains(new ContainerState(137, false));
        assertThat(script.inspects).isEqualTo(4);
    }

    @Test
    void aPauseThatCannotWaitEndsTheRechecking() {
        Script script = script(new ContainerState(137, false));

        var state = ExitClassification.settle(script::inspect, 5, () -> false);

        assertThat(state).contains(new ContainerState(137, false));
        assertThat(script.inspects).isEqualTo(1);
    }

    @Test
    void theRunnerDoesRecheckAndTheWaitIsBoundedToHalfASecond() {
        assertThat(DockerCliSandboxRunner.OOM_FLAG_RECHECKS).isPositive();
        assertThat(DockerCliSandboxRunner.OOM_FLAG_RECHECK_INTERVAL.multipliedBy(
                        DockerCliSandboxRunner.OOM_FLAG_RECHECKS))
                .isLessThanOrEqualTo(java.time.Duration.ofMillis(500));
    }
}
