-- 5.4e: a grade is provisional while a criterion of its rubric that tests do not score (MANUAL,
-- later STATIC_ANALYSIS / OPEN_ANSWER) has no criterion_scores row, i.e. is still pending. The
-- application writes it together with the total, from the same scoring (RubricScoring's
-- hasPending), so the two cannot disagree. The default only serves SQL inserts (seed data, tests).
ALTER TABLE grades ADD COLUMN provisional BOOLEAN NOT NULL DEFAULT FALSE;

-- Grades stored before this migration get the flag their rows already imply.
UPDATE grades g
SET provisional = TRUE
WHERE EXISTS (
    SELECT 1
    FROM submissions s
    JOIN rubric_criteria c ON c.assignment_id = s.assignment_id
    WHERE s.id = g.submission_id
      AND c.type <> 'TESTS'
      AND NOT EXISTS (
          SELECT 1 FROM criterion_scores cs WHERE cs.submission_id = s.id AND cs.criterion_id = c.id));
