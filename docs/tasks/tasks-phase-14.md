# tasks-phase-14.md — AI-Assisted Test Generation

| Commit | Task | Status | Notes |
| --- | --- | --- | --- |
| 14.1a | Migration: `assignments.reference_solution` (nullable text) and `test_proposals` table (assignment\_id, name, input, expected\_output nullable, rationale, status) | Not started | `status` is `PROPOSED`/`ACCEPTED`/`REJECTED`, validated in code. The reference solution is teacher-only: never in a student-facing DTO, never sent to the feedback prompt. |
| 14.1b | Teacher endpoint to set/update the reference solution | Not started | Optional field; proposals still work without it (see 14.3a). |
| 14.2a | AI service `POST /generate-tests`: assignment description, language, existing test inputs → proposed test inputs with a name and rationale, tagged by category (empty input, boundary, large, duplicate values…) | Not started | The LLM proposes **inputs**, not trusted expected outputs. Models are unreliable at computing outputs; the reference solution is. This is the thesis-worthy design choice. |
| 14.2b | Validate and de-duplicate proposals (schema check, drop inputs that match an existing test) | Not started | Malformed LLM output → clean error, as in 6.3c. |
| 14.3a | Backend `POST /assignments/{id}/test-proposals/generate`: call 14.2a, then if a reference solution exists run each proposed input through the sandbox to fill `expected_output` | Not started | A proposal whose reference run times out or errors is flagged, not silently kept. Without a reference solution, proposals have no expected output and the teacher must supply it before accepting. |
| 14.3b | Accept/reject/edit endpoints; accepting creates a real `test_cases` row with the teacher's chosen visibility and criterion | Not started | Invariant: nothing becomes a test case without an explicit teacher action. Subject to the CLAUDE.md lock: once the assignment has submissions, accepting a proposal is rejected with a clear error — generate and accept proposals before publishing. |
| 14.4a | UI: "Suggest tests" button, proposals list with rationale, inline edit, accept (choose public/hidden) and reject | Not started |  |
| 14.5a | Tests (mocked AI): reference run fills expected outputs; a failing reference run flags the proposal | Not started |  |
| 14.5b | Test: proposals never appear in grading until accepted; rejected ones never do | Not started | Directly tests the 14.3b invariant. |
| 14.5c | Test: reference solution absent from every student-facing response and from the feedback prompt | Not started |  |
