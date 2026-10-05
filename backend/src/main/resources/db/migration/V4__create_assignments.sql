CREATE TABLE assignments (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    course_id       BIGINT       NOT NULL REFERENCES courses (id),
    title           VARCHAR(255) NOT NULL,
    description     TEXT         NOT NULL,
    -- Plain VARCHAR validated in the application layer against the languages config,
    -- so adding a language never needs a migration.
    language        VARCHAR(20)  NOT NULL,
    deadline        TIMESTAMPTZ  NOT NULL,
    -- NULL = unlimited attempts.
    max_attempts    INTEGER,
    time_limit_ms   INTEGER      NOT NULL,
    memory_limit_mb INTEGER      NOT NULL,
    -- NULL = no starter code.
    starter_code    TEXT,
    published       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Maintained by the application on every update (no trigger).
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_assignments_course_id ON assignments (course_id);
