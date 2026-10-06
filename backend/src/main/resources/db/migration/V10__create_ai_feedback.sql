CREATE TABLE ai_feedback (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    submission_id BIGINT      NOT NULL REFERENCES submissions (id),
    -- 1 (nudge) / 2 (pointer) / 3 (partial explanation), validated in the application layer.
    hint_level    INTEGER     NOT NULL,
    content       TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- "Report this hint" flow (7.4b). The reason and time stay NULL until the student flags it.
    reported      BOOLEAN     NOT NULL DEFAULT FALSE,
    report_reason TEXT,
    reported_at   TIMESTAMPTZ,
    -- A level is generated once per submission; asking again returns this row (7.2a). The number
    -- of hints used is the row count, so there is no separate counter. The unique index also
    -- serves lookups by submission_id.
    UNIQUE (submission_id, hint_level)
);
