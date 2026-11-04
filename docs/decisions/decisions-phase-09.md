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

## 9.0b — GATE: time and memory limits after the first submission

- **The problem (the phases 0-7 review, M4):** 3.3c locked only the language after the first submission. `SubmissionGrader` reads the time and memory limits from the assignment when it grades, so a teacher's edit reached submissions still `QUEUED` (and, since 9.0a, a re-run): two students with the same code could get different outcomes. `backend/CLAUDE.md`'s 3.4a note ("lock anything that changes what a submission is graded against") contradicted the 3.3c rule.
- **The human's choice (asked in chat, with the trade-offs):** **lock them**, like the language. Chosen over **a snapshot on the submission** (two new columns filled at acceptance, read by the grader and re-runs): that would let a teacher fix a too-tight limit, but the same code submitted before and after the change could still get different results.
  - Accepted cost: a limit that turns out too tight cannot be fixed once students have submitted (the assignment would have to be recreated).
- **How:** in `AssignmentService.update`, under the existing native `FOR UPDATE` row lock (so a submission being inserted is waited for and seen, as for the language). One `hasSubmissions` query covers both locks; it runs only when the language or a limit actually changes, so resending the stored values is never refused.
  - **New 409 `ASSIGNMENT_LIMITS_LOCKED`** ("The assignment has submissions, so its time and memory limits can no longer be changed"), its own code so the frontend (9.2b) can say which field is locked.
  - **Language and a limit changed together: the language's code is reported**, as the bigger change; nothing is stored either way.
  - **Validation still runs first** (unsupported language, memory below the language's minimum, deadline): a 400 for a bad value comes before the 409, as before.
- **Not `lockDraftForGradingChange`:** that also refuses any edit to a published assignment (the 3.5a rule for rubric and tests). Whether the language and limits may change on a published assignment with no submissions yet is 9.0c's question, so this commit keeps their published behaviour unchanged.
- **Docs:** `backend/CLAUDE.md`'s 3.3c note now names the limits as locked; the 3.4a note names the language and limits as the exception to "use `lockDraftForGradingChange`". The root `CLAUDE.md` default "Locked after the first submission" now lists the time and memory limits (the human's decision, so it is a default now).
- **Tests (backend 2725 -> 2730, +5; ai-service and frontend unchanged and passing), `RealHttpAssignmentChangeTests`:**
  - `everythingButTheLanguageStaysEditableAfterASubmission` became `...AndTheLimitsStays...`: it sent new limits, and now sends the stored ones and checks every other field changed; `sendingTheSameLanguageInAnotherSpellingIsNotAChangeEvenWithSubmissions` sends the stored limits too;
  - a changed time limit and a changed memory limit after a submission are each 409 `ASSIGNMENT_LIMITS_LOCKED` with nothing stored;
  - language and limits changed together report the language lock;
  - limits stay editable without submissions;
  - a submission being inserted at that moment decides the limits lock (the edit waits for it, then gets 409).

## 9.0c — GATE: a published assignment's language and limits

- **The problem (the phases 0-7 review, low, courses-assignments):** since 3.5a (option A) a published assignment's rubric and tests are frozen, but `AssignmentService.update` had no published check, so its language and limits could still change until the first submission (3.3c, 9.0b). A student could write Python against a published assignment that the teacher then switched to Java, and the submission would be built as Java.
- **The human's choice (asked in chat):** **freeze them while published**, extending option A to them. Chosen over keeping today's behaviour and only documenting it.
- **How:** in `update`, after the 9.0b submission locks, a changed language or limit on a published assignment is 409 `ASSIGNMENT_PUBLISHED`, the same code as the rubric and tests, so the frontend gives one answer: unpublish, edit, republish (`PublishRules` runs again).
  - **Order as in 3.5a: submissions first** (`ASSIGNMENT_LANGUAGE_LOCKED` / `ASSIGNMENT_LIMITS_LOCKED`), then published, because unpublishing would not help once someone has submitted. 400 validation still comes before both.
  - **Only an actual change is refused**: resending the stored language (any spelling) and limits is accepted, so the other fields (title, description, deadline, attempts, starter code) stay editable on a published assignment, as before.
  - **The code's fixed message** now reads "The assignment is published; unpublish it before changing its language, limits, rubric or tests" (it named only the rubric and tests). The code is unchanged; tests assert codes, not messages.
- **Not done here:** the frontend's message for this code is 9.2b's.
- **Tests (backend 2730 -> 2736, +6; ai-service and frontend unchanged and passing), `RealHttpAssignmentChangeTests`:**
  - a changed language, time limit and memory limit on a published assignment are each 409 `ASSIGNMENT_PUBLISHED`, nothing stored;
  - the other fields stay editable while published;
  - unpublish, change the language, publish again;
  - published with a submission: the submission lock codes are reported, not `ASSIGNMENT_PUBLISHED`.
