CREATE TABLE submissions (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    assignment_id BIGINT      NOT NULL REFERENCES assignments (id),
    student_id    BIGINT      NOT NULL REFERENCES users (id),
    -- Validated against the assignment's language in the application layer.
    language      VARCHAR(20) NOT NULL,
    source_code   TEXT        NOT NULL,
    -- QUEUED / RUNNING / GRADED / ERROR, validated in the application layer.
    status        VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    -- 1-based attempt number for this student on this assignment, assigned by the service.
    attempt_no    INTEGER     NOT NULL,
    submitted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Set when the worker picks the submission up / when grading ends (GRADED or ERROR).
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    -- Two simultaneous requests can never get the same attempt number. The unique index also
    -- serves lookups by (assignment_id, student_id): attempt counting and the best-score query.
    UNIQUE (assignment_id, student_id, attempt_no)
);

-- All submissions of one student across assignments; student_id is not the leading column above.
CREATE INDEX idx_submissions_student_id ON submissions (student_id);
