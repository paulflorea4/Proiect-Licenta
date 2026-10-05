-- Scores are exact decimals, never floating point: a rubric weight times a fraction of passed
-- tests (for example 33 * 2/3) must not accumulate binary rounding error. Two decimal places;
-- the scoring service rounds to that scale.

CREATE TABLE grades (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- One grade per submission.
    submission_id BIGINT        NOT NULL UNIQUE REFERENCES submissions (id),
    total_score   NUMERIC(7, 2) NOT NULL,
    max_score     NUMERIC(7, 2) NOT NULL,
    graded_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE TABLE criterion_scores (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    submission_id BIGINT        NOT NULL REFERENCES submissions (id),
    criterion_id  BIGINT        NOT NULL REFERENCES rubric_criteria (id),
    score         NUMERIC(7, 2) NOT NULL,
    max_score     NUMERIC(7, 2) NOT NULL,
    -- Teacher's note on a manually graded criterion (5.4d); NULL otherwise.
    comment       TEXT,
    -- A criterion with no row yet is "pending", not zero. At most one score per criterion per
    -- submission. The unique index also serves lookups by submission_id.
    UNIQUE (submission_id, criterion_id)
);

CREATE INDEX idx_criterion_scores_criterion_id ON criterion_scores (criterion_id);
