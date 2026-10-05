CREATE TABLE test_cases (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    assignment_id   BIGINT       NOT NULL REFERENCES assignments (id),
    -- Nullable here; the rule that a test belongs to a TESTS criterion is enforced by the service.
    criterion_id    BIGINT REFERENCES rubric_criteria (id),
    name            VARCHAR(255) NOT NULL,
    -- stdin fed to the program and the stdout it must produce. Either may legitimately be empty.
    input           TEXT         NOT NULL,
    expected_output TEXT         NOT NULL,
    -- PUBLIC / HIDDEN, validated in the application layer. Hidden input and expected output
    -- must never reach students or the LLM.
    visibility      VARCHAR(10)  NOT NULL,
    weight          INTEGER      NOT NULL DEFAULT 1,
    -- Display / run order within the assignment, set by the service.
    position        INTEGER      NOT NULL
);

CREATE INDEX idx_test_cases_assignment_id ON test_cases (assignment_id);
CREATE INDEX idx_test_cases_criterion_id ON test_cases (criterion_id);
