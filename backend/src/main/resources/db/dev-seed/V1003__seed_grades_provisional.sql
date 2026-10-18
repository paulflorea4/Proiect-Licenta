-- DEV-ONLY (5.4e): the provisional flag of the grades seeded by V1002. On a new database V11 adds
-- the column before V1002 runs, so those inserts get the default (FALSE); this sets each from its
-- pending criteria, with the same rule as V11. Four seeded grades have a MANUAL criterion still
-- pending (see the V1002 header). Dev-seed location only; see V1000__seed_users.sql.
UPDATE grades g
SET provisional = EXISTS (
    SELECT 1
    FROM submissions s
    JOIN rubric_criteria c ON c.assignment_id = s.assignment_id
    WHERE s.id = g.submission_id
      AND c.type <> 'TESTS'
      AND NOT EXISTS (
          SELECT 1 FROM criterion_scores cs WHERE cs.submission_id = s.id AND cs.criterion_id = c.id));
