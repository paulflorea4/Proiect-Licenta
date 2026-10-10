package com.gradingplatform.backend.dto;

import com.gradingplatform.backend.entity.TestCase;
import com.gradingplatform.backend.entity.TestVisibility;

/**
 * A test case as a student may see it (3.5b). The masking is done here, in the type, not in the
 * frontend: a {@link HiddenTest} has no component that could carry the hidden test's
 * input, expected output or real name, so no code path can serialise them, whatever a caller does
 * with it. {@code StudentTestCaseResponseTests} pins the component names.
 *
 * <p>What a student never gets, for either kind: the criterion (the rubric is not visible to
 * students yet) and the entity's assignment id. The same rule feeds the AI prompt in Phase 6: build
 * its test section from this type, never from a {@code TestCase}.
 */
public sealed interface StudentTestCaseResponse
        permits StudentTestCaseResponse.PublicTest, StudentTestCaseResponse.HiddenTest {

    Long id();

    /** The name students see for a hidden test: its number among the hidden tests, in run order. */
    static String maskedName(int hiddenOrdinal) {
        return "Hidden test " + hiddenOrdinal;
    }

    /** A public test, in full. */
    record PublicTest(
            Long id,
            String name,
            String input,
            String expectedOutput,
            TestVisibility visibility,
            int weight,
            int position)
            implements StudentTestCaseResponse {

        static PublicTest of(TestCase t) {
            return new PublicTest(
                    t.getId(),
                    t.getName(),
                    t.getInput(),
                    t.getExpectedOutput(),
                    t.getVisibility(),
                    t.getWeight(),
                    t.getPosition());
        }
    }

    /** A hidden test: that it exists, where it runs and a generic name; nothing of its content. */
    record HiddenTest(Long id, String name, TestVisibility visibility, int position)
            implements StudentTestCaseResponse {

        static HiddenTest of(TestCase t, int hiddenOrdinal) {
            return new HiddenTest(t.getId(), maskedName(hiddenOrdinal), t.getVisibility(), t.getPosition());
        }
    }
}
