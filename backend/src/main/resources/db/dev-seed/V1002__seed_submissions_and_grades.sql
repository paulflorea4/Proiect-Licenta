-- DEV-ONLY seed data (1.4c): seven graded submissions with their test results, grades and
-- criterion scores, so the dashboards of later phases have something to aggregate before any
-- real submission exists. Dev-seed location only; see V1000__seed_users.sql. Depends on V1001.
--
-- Every number below was worked out by hand from the rubrics in V1001 (a TESTS criterion scores
-- weight x passed test weight / total test weight; the total is the sum of the criterion scores;
-- a MANUAL criterion the teacher has not scored yet has NO criterion_scores row, i.e. it is
-- pending, not zero). max_score of a criterion is its weight; max_score of a grade is 100.
--
--   student1  Sum of Two Numbers  #1  compile error                           0.00 (Code quality pending)
--   student1  Sum of Two Numbers  #2  overflow test fails: 80 x 4/6 = 53.33
--                                     + Code quality 15.00                    68.33
--   student2  Sum of Two Numbers  #1  all pass: 80 + Code quality 20          100.00
--   student1  Palindrome Check    #1  every test crashes                       0.00 (Readability pending)
--   student1  Palindrome Check    #2  Basic 40 x 1/3 = 13.33, Edge 40 x 2/2    53.33 (Readability pending)
--   student2  Palindrome Check    #1  Basic 40 x 3/3, Edge 40 x 1/2 = 20       60.00 (Readability pending)
--   student2  Palindrome Check    #2  Basic 40, Edge 40 + Readability 18        98.00
--
-- Submissions are keyed by (student email, assignment title, attempt number) in the statements
-- below, because ids are generated. Timestamps are relative to when the seed runs.

INSERT INTO submissions (assignment_id, student_id, language, source_code, status, attempt_no,
                         submitted_at, started_at, finished_at)
SELECT a.id, u.id, a.language, v.source_code, 'GRADED', v.attempt_no,
       NOW() - v.age,
       NOW() - v.age + INTERVAL '1 second',
       NOW() - v.age + INTERVAL '4 seconds'
FROM (VALUES
    ('student1@dev.example.com', 'Sum of Two Numbers', 1, INTERVAL '6 days',
     E'import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner in = new Scanner(System.in);\n        int a = in.nextInt();\n        int b = in.nextInt()\n        System.out.println(a + b);\n    }\n}\n'),
    ('student1@dev.example.com', 'Sum of Two Numbers', 2, INTERVAL '5 days',
     E'import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner in = new Scanner(System.in);\n        int a = in.nextInt();\n        int b = in.nextInt();\n        System.out.println(a + b);\n    }\n}\n'),
    ('student2@dev.example.com', 'Sum of Two Numbers', 1, INTERVAL '4 days',
     E'import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner in = new Scanner(System.in);\n        long a = in.nextLong();\n        long b = in.nextLong();\n        System.out.println(a + b);\n    }\n}\n'),
    ('student1@dev.example.com', 'Palindrome Check', 1, INTERVAL '3 days',
     E'print("YES" if word == word[::-1] else "NO")\n'),
    ('student1@dev.example.com', 'Palindrome Check', 2, INTERVAL '2 days 12 hours',
     E'w = input()\ni = 0\nwhile i < len(w) // 2:\n    if w[i] != w[-1 - i]:\n        print("NO")\n        break\nelse:\n    print("YES")\n'),
    ('student2@dev.example.com', 'Palindrome Check', 1, INTERVAL '2 days',
     E'w = input().lower()\nprint("YES" if w == w[::-1] else "NO")\n'),
    ('student2@dev.example.com', 'Palindrome Check', 2, INTERVAL '1 day',
     E'w = input()\nprint("YES" if w == w[::-1] else "NO")\n')
) AS v (email, title, attempt_no, age, source_code)
JOIN users u ON u.email = v.email
JOIN assignments a ON a.title = v.title;

INSERT INTO test_results (submission_id, test_case_id, status, actual_output, stderr, runtime_ms)
SELECT s.id, t.id, v.status, v.actual_output, v.stderr, v.runtime_ms
FROM (VALUES
    -- student1, Sum of Two Numbers #1: does not compile, so no test ran.
    ('student1@dev.example.com', 'Sum of Two Numbers', 1, 'Small numbers',    'COMPILE_ERROR', NULL, E'Main.java:7: error: '';'' expected', NULL),
    ('student1@dev.example.com', 'Sum of Two Numbers', 1, 'Negative number',  'COMPILE_ERROR', NULL, E'Main.java:7: error: '';'' expected', NULL),
    ('student1@dev.example.com', 'Sum of Two Numbers', 1, 'Zero',             'COMPILE_ERROR', NULL, E'Main.java:7: error: '';'' expected', NULL),
    ('student1@dev.example.com', 'Sum of Two Numbers', 1, 'Both negative',    'COMPILE_ERROR', NULL, E'Main.java:7: error: '';'' expected', NULL),
    ('student1@dev.example.com', 'Sum of Two Numbers', 1, 'Overflows an int', 'COMPILE_ERROR', NULL, E'Main.java:7: error: '';'' expected', NULL),
    -- student1, Sum of Two Numbers #2: int arithmetic overflows on the big input (2e9 + 2e9 wraps).
    ('student1@dev.example.com', 'Sum of Two Numbers', 2, 'Small numbers',    'PASSED', '3',          NULL, 112),
    ('student1@dev.example.com', 'Sum of Two Numbers', 2, 'Negative number',  'PASSED', '0',          NULL, 108),
    ('student1@dev.example.com', 'Sum of Two Numbers', 2, 'Zero',             'PASSED', '0',          NULL, 105),
    ('student1@dev.example.com', 'Sum of Two Numbers', 2, 'Both negative',    'PASSED', '-10',        NULL, 110),
    ('student1@dev.example.com', 'Sum of Two Numbers', 2, 'Overflows an int', 'FAILED', '-294967296', NULL, 109),
    -- student2, Sum of Two Numbers #1: everything passes.
    ('student2@dev.example.com', 'Sum of Two Numbers', 1, 'Small numbers',    'PASSED', '3',          NULL, 120),
    ('student2@dev.example.com', 'Sum of Two Numbers', 1, 'Negative number',  'PASSED', '0',          NULL, 118),
    ('student2@dev.example.com', 'Sum of Two Numbers', 1, 'Zero',             'PASSED', '0',          NULL, 116),
    ('student2@dev.example.com', 'Sum of Two Numbers', 1, 'Both negative',    'PASSED', '-10',        NULL, 119),
    ('student2@dev.example.com', 'Sum of Two Numbers', 1, 'Overflows an int', 'PASSED', '4000000000', NULL, 121),
    -- student1, Palindrome Check #1: the word is never read, so every run crashes.
    ('student1@dev.example.com', 'Palindrome Check', 1, 'Palindrome',       'RUNTIME_ERROR', NULL, E'Traceback (most recent call last):\n  File "main.py", line 1, in <module>\nNameError: name "word" is not defined', 31),
    ('student1@dev.example.com', 'Palindrome Check', 1, 'Not a palindrome', 'RUNTIME_ERROR', NULL, E'Traceback (most recent call last):\n  File "main.py", line 1, in <module>\nNameError: name "word" is not defined', 30),
    ('student1@dev.example.com', 'Palindrome Check', 1, 'Even length',      'RUNTIME_ERROR', NULL, E'Traceback (most recent call last):\n  File "main.py", line 1, in <module>\nNameError: name "word" is not defined', 30),
    ('student1@dev.example.com', 'Palindrome Check', 1, 'Single character', 'RUNTIME_ERROR', NULL, E'Traceback (most recent call last):\n  File "main.py", line 1, in <module>\nNameError: name "word" is not defined', 29),
    ('student1@dev.example.com', 'Palindrome Check', 1, 'Case-sensitive',   'RUNTIME_ERROR', NULL, E'Traceback (most recent call last):\n  File "main.py", line 1, in <module>\nNameError: name "word" is not defined', 31),
    -- student1, Palindrome Check #2: the loop index is never advanced, so any word whose first
    -- and last letters match (and longer than one letter) loops until the time limit.
    ('student1@dev.example.com', 'Palindrome Check', 2, 'Palindrome',       'TIMEOUT', NULL, NULL, 2000),
    ('student1@dev.example.com', 'Palindrome Check', 2, 'Not a palindrome', 'PASSED',  'NO',  NULL, 28),
    ('student1@dev.example.com', 'Palindrome Check', 2, 'Even length',      'TIMEOUT', NULL, NULL, 2000),
    ('student1@dev.example.com', 'Palindrome Check', 2, 'Single character', 'PASSED',  'YES', NULL, 27),
    ('student1@dev.example.com', 'Palindrome Check', 2, 'Case-sensitive',   'PASSED',  'NO',  NULL, 28),
    -- student2, Palindrome Check #1: lower-cases the word, so "Madam" is wrongly a palindrome.
    ('student2@dev.example.com', 'Palindrome Check', 1, 'Palindrome',       'PASSED', 'YES', NULL, 33),
    ('student2@dev.example.com', 'Palindrome Check', 1, 'Not a palindrome', 'PASSED', 'NO',  NULL, 32),
    ('student2@dev.example.com', 'Palindrome Check', 1, 'Even length',      'PASSED', 'YES', NULL, 32),
    ('student2@dev.example.com', 'Palindrome Check', 1, 'Single character', 'PASSED', 'YES', NULL, 31),
    ('student2@dev.example.com', 'Palindrome Check', 1, 'Case-sensitive',   'FAILED', 'YES', NULL, 33),
    -- student2, Palindrome Check #2: everything passes.
    ('student2@dev.example.com', 'Palindrome Check', 2, 'Palindrome',       'PASSED', 'YES', NULL, 30),
    ('student2@dev.example.com', 'Palindrome Check', 2, 'Not a palindrome', 'PASSED', 'NO',  NULL, 29),
    ('student2@dev.example.com', 'Palindrome Check', 2, 'Even length',      'PASSED', 'YES', NULL, 29),
    ('student2@dev.example.com', 'Palindrome Check', 2, 'Single character', 'PASSED', 'YES', NULL, 28),
    ('student2@dev.example.com', 'Palindrome Check', 2, 'Case-sensitive',   'PASSED', 'NO',  NULL, 30)
) AS v (email, title, attempt_no, test_name, status, actual_output, stderr, runtime_ms)
JOIN users u ON u.email = v.email
JOIN assignments a ON a.title = v.title
JOIN submissions s ON s.assignment_id = a.id AND s.student_id = u.id AND s.attempt_no = v.attempt_no
JOIN test_cases t ON t.assignment_id = a.id AND t.name = v.test_name;

INSERT INTO criterion_scores (submission_id, criterion_id, score, max_score, comment)
SELECT s.id, c.id, v.score, c.weight, v.comment
FROM (VALUES
    ('student1@dev.example.com', 'Sum of Two Numbers', 1, 'Correctness',  0.00, NULL),
    ('student1@dev.example.com', 'Sum of Two Numbers', 2, 'Correctness',  53.33, NULL),
    ('student1@dev.example.com', 'Sum of Two Numbers', 2, 'Code quality', 15.00, 'Clear names; read both numbers with long to avoid overflow.'),
    ('student2@dev.example.com', 'Sum of Two Numbers', 1, 'Correctness',  80.00, NULL),
    ('student2@dev.example.com', 'Sum of Two Numbers', 1, 'Code quality', 20.00, 'Tidy and correct.'),
    ('student1@dev.example.com', 'Palindrome Check',   1, 'Basic cases',  0.00, NULL),
    ('student1@dev.example.com', 'Palindrome Check',   1, 'Edge cases',   0.00, NULL),
    ('student1@dev.example.com', 'Palindrome Check',   2, 'Basic cases',  13.33, NULL),
    ('student1@dev.example.com', 'Palindrome Check',   2, 'Edge cases',   40.00, NULL),
    ('student2@dev.example.com', 'Palindrome Check',   1, 'Basic cases',  40.00, NULL),
    ('student2@dev.example.com', 'Palindrome Check',   1, 'Edge cases',   20.00, NULL),
    ('student2@dev.example.com', 'Palindrome Check',   2, 'Basic cases',  40.00, NULL),
    ('student2@dev.example.com', 'Palindrome Check',   2, 'Edge cases',   40.00, NULL),
    ('student2@dev.example.com', 'Palindrome Check',   2, 'Readability',  18.00, 'Concise; a short comment would help.')
) AS v (email, title, attempt_no, criterion, score, comment)
JOIN users u ON u.email = v.email
JOIN assignments a ON a.title = v.title
JOIN submissions s ON s.assignment_id = a.id AND s.student_id = u.id AND s.attempt_no = v.attempt_no
JOIN rubric_criteria c ON c.assignment_id = a.id AND c.name = v.criterion;

INSERT INTO grades (submission_id, total_score, max_score)
SELECT s.id, v.total_score, 100.00
FROM (VALUES
    ('student1@dev.example.com', 'Sum of Two Numbers', 1, 0.00),
    ('student1@dev.example.com', 'Sum of Two Numbers', 2, 68.33),
    ('student2@dev.example.com', 'Sum of Two Numbers', 1, 100.00),
    ('student1@dev.example.com', 'Palindrome Check',   1, 0.00),
    ('student1@dev.example.com', 'Palindrome Check',   2, 53.33),
    ('student2@dev.example.com', 'Palindrome Check',   1, 60.00),
    ('student2@dev.example.com', 'Palindrome Check',   2, 98.00)
) AS v (email, title, attempt_no, total_score)
JOIN users u ON u.email = v.email
JOIN assignments a ON a.title = v.title
JOIN submissions s ON s.assignment_id = a.id AND s.student_id = u.id AND s.attempt_no = v.attempt_no;
