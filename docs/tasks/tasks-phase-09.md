# tasks-phase-09.md — React: Courses, Assignments & Submissions

| Commit | Task | Status | Notes |
| --- | --- | --- | --- |
| 9.1a | Teacher: course list page + create-course form | Not started | Shows the enrollment code prominently with a copy action. |
| 9.1b | Student: "join course" form (enrollment code) + my courses list | Not started | Empty state matters here — a new student has no courses, and the page should say how to join, not look broken. |
| 9.2a | Course page: assignment list (students see published only) with deadline and best score | Not started |  |
| 9.2b | Teacher: assignment create/edit form (language, deadline, limits, starter code, publish toggle) | Not started | Surface the server-side validation errors from 3.3a inline. |
| 9.2c | Teacher: rubric editor with a live "weights sum to 100" indicator | Not started | The indicator is a convenience; 3.4b's server-side check is authoritative. |
| 9.2d | Teacher: test case editor (name, stdin, expected stdout, public/hidden, criterion) | Not started | Multi-line textareas with visible whitespace — trailing-space bugs are invisible otherwise. |
| 9.3a | Student: assignment detail page (description, public tests, deadline countdown, attempts left) | Not started | Hidden tests appear only as "N hidden tests", per 3.5b. |
| 9.4a | In-browser code editor for submission with the language preset and starter code | Not started | Adding an editor library (e.g. Monaco) is a new dependency — gate applies. A plain `<textarea>` is the acceptable fallback if the human declines. |
| 9.4b | File-upload alternative wired to 5.1b | Not started |  |
| 9.5a | Submission result page: total score, rubric breakdown, per-test pass/fail with public details and masked hidden tests | Not started | Renders exactly what 5.5a returns — no client-side masking logic. |
| 9.5b | Poll the submission status until `GRADED`/`ERROR` | Not started | Temporary: Phase 11 replaces polling with WebSocket updates (keep polling as the fallback). Back off the interval. |
| 9.5c | Hint panel: "Get a hint" button, shows received hints by level and "hints used" | Not started | Wired to 7.2a/7.2b. Show the 409 and 503 cases as friendly messages, not raw errors. |
| 9.5d | "Report this hint" button with an optional reason on each hint, wired to 7.4b; reported hints show as flagged | Not started | Confirm the flag visibly so students know it was recorded; don't hide the hint after reporting. |
| 9.6a | Submission history per assignment with scores and statuses | Not started |  |
| 9.6b | Teacher: per-assignment submissions list (student, status, score, attempt, hints used) and a full-detail submission page including hidden tests and the submitted code | Not started | Wired to 5.5b and 5.5c. Without this page teachers have no way to look at an individual submission. |
| 9.6c | Teacher: manual grading form for `MANUAL` criteria on the submission page (score within range + comment), shows the recomputed total and the provisional flag | Not started | Wired to 5.4d and 5.4e. Show server-side range errors inline. |
| 9.7a | RTL tests: course creation and join flows | Not started |  |
| 9.7b | RTL tests: result page renders pass/fail correctly and never shows hidden-test content | Not started |  |
| 9.7c | RTL tests: hint panel shows levels in order, handles error states, and the report-hint flow | Not started |  |
| 9.7d | RTL tests: teacher submission page shows hidden-test detail, manual grading form validates and updates the total | Not started |  |
