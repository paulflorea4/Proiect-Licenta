CREATE TABLE enrollments (
    course_id   BIGINT      NOT NULL REFERENCES courses (id),
    student_id  BIGINT      NOT NULL REFERENCES users (id),
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- A student joins a course at most once. Also indexes lookups by course_id (leading column).
    PRIMARY KEY (course_id, student_id)
);

-- "Which courses is this student in" cannot use the primary key (student_id is its second column).
CREATE INDEX idx_enrollments_student_id ON enrollments (student_id);
