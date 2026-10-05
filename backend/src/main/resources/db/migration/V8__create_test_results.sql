CREATE TABLE test_results (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    submission_id BIGINT      NOT NULL REFERENCES submissions (id),
    test_case_id  BIGINT      NOT NULL REFERENCES test_cases (id),
    -- PASSED / FAILED / TIMEOUT / MEMORY_LIMIT / RUNTIME_ERROR / COMPILE_ERROR,
    -- validated in the application layer.
    status        VARCHAR(20) NOT NULL,
    -- Both are stored already truncated by the sandbox output cap (4.3c). NULL when the run
    -- produced nothing to record (for example a compile error has no program output).
    actual_output TEXT,
    stderr        TEXT,
    runtime_ms    INTEGER,
    -- One result per test per submission. The unique index also serves lookups by submission_id.
    UNIQUE (submission_id, test_case_id)
);

CREATE INDEX idx_test_results_test_case_id ON test_results (test_case_id);
