CREATE TABLE rubric_criteria (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    assignment_id BIGINT       NOT NULL REFERENCES assignments (id),
    name          VARCHAR(255) NOT NULL,
    -- TESTS / STATIC_ANALYSIS / OPEN_ANSWER / MANUAL, validated in the application layer
    -- so later phases add types without a migration.
    type          VARCHAR(30)  NOT NULL,
    -- Whole points out of 100 for the assignment; the sum-to-100 rule is enforced by the service.
    weight        INTEGER      NOT NULL,
    -- Type-specific parameters added by later phases; NULL when a type needs none.
    config        JSONB
);

CREATE INDEX idx_rubric_criteria_assignment_id ON rubric_criteria (assignment_id);
