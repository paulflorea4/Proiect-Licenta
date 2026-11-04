# decisions-phase-09.md — React: Courses, Assignments & Submissions

## 9.0a — GATE: a submission that ends in `ERROR` after the deadline

- **The problem (the phases 0-7 review, M3):** a submission ends in `ERROR` when grading cannot give a result: the sandbox fails (Docker down, an image or command missing), the language was removed from the config, or an unexpected exception. `ERROR` gives the attempt back (5.1d), but after the deadline a new submission is refused and nothing re-runs an `ERROR`, so a failure in the last minutes leaves the student with no grade and no way to get one.
- **The human's choice (asked in chat, with the trade-offs):** a **teacher re-run**. It was chosen over:
  - **a late resubmission** (the student could change the code after the deadline);
  - **an automatic retry on `SANDBOX_ERROR`** (no help in a long outage, and blind to other errors);
  - **both of those together.**

  Recommended because the same code is graded at the same arrival time: nothing about the attempt changes after the deadline.
- **The endpoint, `POST /submissions/{submissionId}/rerun`** (no body, answers 202 with the same `SubmissionAcceptedResponse` as a new submission, `QUEUED`):
  - **Who:** `hasAnyRole('TEACHER', 'ADMIN')` plus `requireManageable` on the submission's assignment, whose refusal becomes the 404 `SUBMISSION_NOT_FOUND` of a missing id (the 5.4d manual-score pattern). A student, the author included, is 403 by role. Re-running belongs to whoever grades.
  - **Only an `ERROR`:** a new 409 `SUBMISSION_NOT_IN_ERROR` ("Only a submission that ended in error can be run again"). The check is the update itself: `requeueError` is one native statement, `... where id = :id and status = 'ERROR'`, so of two re-runs at once exactly one wins, and the other gets the 409 with nothing changed.
  - **What changes:** `status` to `QUEUED`; `started_at` and `finished_at` to null (the worker sets them anew). **Kept:** code, attempt number, `submitted_at`. Its `test_results` are deleted in the same transaction, since 5.2c's invariant is that a `QUEUED` row has none (an `ERROR` normally has none, but a cut-short run could leave some). Grade rows need nothing: storing replaces them (5.4b).
  - **To the worker:** the existing `SubmissionQueuedEvent`, offered after commit (5.2a), exactly like a new submission. If the pool is full the row simply stays `QUEUED` and is picked up later.
- **Not judged again: the deadline and `max_attempts`.** It is the attempt the student already made, at its original arrival; its `ERROR` gave the attempt back (5.1d), and the re-run takes it again. If the student has since used the limit, the re-run still runs, and once `GRADED` it counts, so a student can end with one counted attempt more than `max_attempts`. The teacher decided to re-run it, so that is accepted.
- **No automatic retry and no frontend here:** the "Run again" action belongs to 9.6b's teacher submission page; its row's notes now say so.
- **Tests (backend 2671 -> 2725, +54: 11 in the new class, the rest from the matrices' parameterized rows; ai-service and frontend unchanged and passing):**
  - **`RealHttpSubmissionRerunTests`** (new, 11, real HTTP):
    - the owner's re-run: 202 `QUEUED` with the original `submittedAt`, then `GRADED` by the real worker with the same code, attempt and arrival, and a new start and finish;
    - an admin may;
    - a passed deadline and a used-up limit don't stop it;
    - stale test results are deleted;
    - `GRADED` and `RUNNING` are 409 and left exactly as they were; `QUEUED` is 409;
    - another teacher gets the missing id's 404 byte for byte, and nothing changes;
    - a student is 403; anonymous is 401;
    - of two re-runs at once, exactly one is 202.
  - **`SubmissionAuthorizationMatrixTests`:** a new `FAILED` setup (the author's submission in `ERROR`) with a row for all six callers (owner and admin 202, another teacher 404, students 403), and a row on `GRADED` (owner and admin 409); refused callers change nothing.
  - **`RoleMatrixIntegrationTests`:** the role row.
- **Mutation-checked:**
  - any state re-run (no `status = 'ERROR'` condition): 4 failures
  - stale results kept: 1
  - the worker not told (no event): 5
  - no course check: 1
  - the arrival reset to now: 1
