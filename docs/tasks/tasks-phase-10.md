# tasks-phase-10.md — Teacher Dashboard & Analytics

| Commit | Task | Status | Notes |
| --- | --- | --- | --- |
| 10.1a | `GET /assignments/{id}/stats`: submission count, students submitted, mean/median/min/max of each student's best score | Not started | Statistics are over each student's best score (the counting policy), not over every attempt — otherwise repeated tries skew the mean. |
| 10.1b | Score distribution: fixed-width buckets (e.g. 0–10%, …, 90–100%) | Not started | Bucket width is a named constant. |
| 10.1c | Per-criterion averages | Not started |  |
| 10.2a | Per-test pass rate, sorted hardest first | Not started | This is the "common mistakes" signal that needs no AI: the tests most students fail point at the concept most students miss. |
| 10.2b | Failure-type breakdown: compile errors, timeouts, runtime errors, wrong output | Not started |  |
| 10.2c | Optional AI summary of common mistakes: backend sends aggregated pass rates and failure types (never raw student code or identities) to a new `POST /summarize-mistakes` in the AI service | Not started | Aggregated data only. Cached per assignment; the teacher refreshes it on demand rather than on every page load, to bound LLM usage. Runs on the teacher's request, within the timeout, outside any DB transaction. |
| 10.3a | `GET /courses/{id}/progress`: per-student table of best score per assignment | Not started |  |
| 10.3b | `GET /courses/{id}/grades.csv`: grade export | Not started | Escape cells that start with `=`, `+`, `-`, `@` to prevent spreadsheet formula injection from student-controlled names. |
| 10.4a | Dashboard page: summary cards (submissions, mean, median) | Not started | Authorization: course owner or admin only. |
| 10.4b | Score distribution chart | Not started | A charting library is a new dependency — gate applies. Simple CSS/SVG bars are the fallback if the human declines. |
| 10.4c | Common-mistakes panel (hardest tests, failure types, optional AI summary) | Not started |  |
| 10.4d | Student progress table (sortable) + CSV export button | Not started |  |
| 10.5a | Backend tests with hand-computed statistics on a small fixed dataset (seed from 1.4c) | Not started | Compute mean/median/buckets by hand — don't derive expected values by running the code. |
| 10.5b | Authorization tests: students and other teachers get 403 on all analytics endpoints | Not started | Add the endpoints to the role matrix style from 2.7d. |
| 10.5c | Frontend tests: dashboard renders stats, empty-state with zero submissions | Not started | Zero submissions must not divide by zero or render NaN. |
