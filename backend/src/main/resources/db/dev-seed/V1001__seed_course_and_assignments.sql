-- DEV-ONLY seed data (1.4b): one course with both students enrolled and two published
-- assignments (Java and Python), each with a rubric that sums to 100 and tests of both
-- visibilities. Dev-seed location only; see V1000__seed_users.sql. Rows are found by natural
-- keys (email, title, criterion name) instead of hard-coded ids, because ids are generated.
--
-- Deadlines are a fixed far-future date so the assignments stay open however long a dev
-- database lives.

INSERT INTO courses (title, description, teacher_id, enroll_code)
VALUES ('Introduction to Programming',
        'Seeded dev course.',
        (SELECT id FROM users WHERE email = 'teacher@dev.example.com'),
        'DEVSEED');

INSERT INTO enrollments (course_id, student_id)
SELECT (SELECT id FROM courses WHERE enroll_code = 'DEVSEED'), id
FROM users
WHERE email IN ('student1@dev.example.com', 'student2@dev.example.com');

-- Assignment 1: Java, unlimited attempts, with starter code.
INSERT INTO assignments (course_id, title, description, language, deadline, max_attempts,
                         time_limit_ms, memory_limit_mb, starter_code, published)
VALUES ((SELECT id FROM courses WHERE enroll_code = 'DEVSEED'),
        'Sum of Two Numbers',
        'Read two integers separated by a space from standard input and print their sum. '
            || 'The values can be as large as 2 000 000 000, so their sum may not fit in an int.',
        'JAVA',
        '2030-12-31 23:59:00+00',
        NULL,
        5000,
        256,
        'import java.util.Scanner;' || E'\n\n'
            || 'public class Main {' || E'\n'
            || '    public static void main(String[] args) {' || E'\n'
            || '        Scanner in = new Scanner(System.in);' || E'\n'
            || '        // TODO: read two numbers and print their sum' || E'\n'
            || '    }' || E'\n'
            || '}' || E'\n',
        TRUE);

INSERT INTO rubric_criteria (assignment_id, name, type, weight)
SELECT id, criterion.name, criterion.type, criterion.weight
FROM assignments,
     (VALUES ('Correctness', 'TESTS', 80), ('Code quality', 'MANUAL', 20)) AS criterion (name, type, weight)
WHERE title = 'Sum of Two Numbers';

INSERT INTO test_cases (assignment_id, criterion_id, name, input, expected_output, visibility, weight, position)
SELECT a.id,
       (SELECT c.id FROM rubric_criteria c WHERE c.assignment_id = a.id AND c.name = 'Correctness'),
       t.name, t.input, t.expected_output, t.visibility, t.weight, t.position
FROM assignments a,
     (VALUES ('Small numbers',    E'1 2\n',                   '3',          'PUBLIC', 1, 1),
             ('Negative number',  E'-5 5\n',                  '0',          'PUBLIC', 1, 2),
             ('Zero',             E'0 0\n',                   '0',          'HIDDEN', 1, 3),
             ('Both negative',    E'-7 -3\n',                 '-10',        'HIDDEN', 1, 4),
             ('Overflows an int', E'2000000000 2000000000\n', '4000000000', 'HIDDEN', 2, 5))
         AS t (name, input, expected_output, visibility, weight, position)
WHERE a.title = 'Sum of Two Numbers';

-- Assignment 2: Python, at most 3 attempts, no starter code. Two TESTS criteria and one MANUAL.
INSERT INTO assignments (course_id, title, description, language, deadline, max_attempts,
                         time_limit_ms, memory_limit_mb, starter_code, published)
VALUES ((SELECT id FROM courses WHERE enroll_code = 'DEVSEED'),
        'Palindrome Check',
        'Read one word from standard input and print YES if it reads the same forwards and '
            || 'backwards, otherwise NO. The check is case-sensitive.',
        'PYTHON',
        '2030-12-31 23:59:00+00',
        3,
        2000,
        256,
        NULL,
        TRUE);

INSERT INTO rubric_criteria (assignment_id, name, type, weight)
SELECT id, criterion.name, criterion.type, criterion.weight
FROM assignments,
     (VALUES ('Basic cases', 'TESTS', 40), ('Edge cases', 'TESTS', 40), ('Readability', 'MANUAL', 20))
         AS criterion (name, type, weight)
WHERE title = 'Palindrome Check';

INSERT INTO test_cases (assignment_id, criterion_id, name, input, expected_output, visibility, weight, position)
SELECT a.id,
       (SELECT c.id FROM rubric_criteria c WHERE c.assignment_id = a.id AND c.name = t.criterion),
       t.name, t.input, t.expected_output, t.visibility, 1, t.position
FROM assignments a,
     (VALUES ('Palindrome',       'Basic cases', E'level\n', 'YES', 'PUBLIC', 1),
             ('Not a palindrome', 'Basic cases', E'hello\n', 'NO',  'PUBLIC', 2),
             ('Even length',      'Basic cases', E'abba\n',  'YES', 'HIDDEN', 3),
             ('Single character', 'Edge cases',  E'a\n',     'YES', 'HIDDEN', 4),
             ('Case-sensitive',   'Edge cases',  E'Madam\n', 'NO',  'HIDDEN', 5))
         AS t (name, criterion, input, expected_output, visibility, position)
WHERE a.title = 'Palindrome Check';
