CREATE TABLE courses (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    description TEXT,
    teacher_id  BIGINT       NOT NULL REFERENCES users (id),
    -- What students type to join. Short; generated in the service (3.1a), unique here.
    enroll_code VARCHAR(20)  NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Postgres does not index foreign keys automatically; "a teacher's courses" is a core query.
CREATE INDEX idx_courses_teacher_id ON courses (teacher_id);
