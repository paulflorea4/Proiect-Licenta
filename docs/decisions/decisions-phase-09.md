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

## 9.0d — GATE: the roster and users promoted to teacher

- **The problem (the phases 0-7 review, low, courses-assignments):** a role change kept the user's enrollment rows, so `GET /courses/{id}/students` (3.2b, "always STUDENT") listed a promoted teacher or admin, who could no longer use the course as a student (access goes by the token's role).
- **The human's choice (asked in chat):** **drop the enrollments on promotion**, over filtering the roster by role. Recommended because the invariant "enrollments hold only students" then lives in one place, and the roster and every later list built on enrollments (the Phase 10 gradebook, counts) need no filter. Accepted cost: a promotion undone by a demotion does not bring the courses back; the user rejoins with the codes.
- **How:**
  - **`UserAdminService.changeRole`:** when the new role is not `STUDENT`, `EnrollmentRepository.deleteByStudentId` runs in the same transaction, after the existing checks (so a refused change, e.g. the 7.7e stale admin, removes nothing). It runs even when the role does not actually change (`TEACHER` to `TEACHER`): harmless, and it cleans a leftover. Submissions, test results and grades are untouched, as when a student leaves a course (3.2b); the teacher views of them still show the user.
  - **Joining checks the stored role (an addition to the row, needed for the invariant):** a promoted user keeps a `STUDENT` token for up to 24 h (2.4b) and could otherwise rejoin straight away. `EnrollmentService.enroll` first reads the role with `UserRepository.lockRoleForShare` (native `FOR SHARE`); not `STUDENT` is 403 `ACCESS_DENIED` (the 7.7e pattern: a stale token is refused by the stored role), and a deleted user is the existing 401 `ACCOUNT_NO_LONGER_EXISTS`. The check runs before the code lookup, so a non-student learns nothing about codes.
  - **Race, both orders closed:** the promotion flushes its `UPDATE` of the user's row before deleting the enrollments (Hibernate would otherwise write it only at commit, after the delete). A join holding the role `FOR SHARE` makes the promotion's update wait, and the delete that follows sees the new enrollment; a promotion holding the row makes the join's `FOR SHARE` wait, and it then reads the new role and is refused. The foreign key's own `FOR KEY SHARE` would not have conflicted with the role update (`FOR NO KEY UPDATE`), hence the explicit lock.
  - **V12 (data only):** deletes the enrollments of users whose role is not `STUDENT`, for promotions made before this commit (the dev seed enrolls only students). Flyway counts: prod 11 -> 12, dev 15 -> 16.
- **Not changed:** the roster query; a student's other actions with a stale token (a submission needs the enrollment, now gone, so it is the 404 of an unseen assignment).
- **Tests (backend 2736 -> 2746, +10; ai-service and frontend unchanged and passing):**
  - **`RealHttpPromotionEnrollmentTests`** (8): promotion to `TEACHER` and to `ADMIN` removes every enrollment and the roster entry, a classmate is untouched; the submissions stay; staying or becoming a student keeps / allows enrollments; a refused role change keeps them; the old student token cannot rejoin (403); a join waits for a promotion in flight and is refused; a promotion waits for a join in flight and then removes it.
  - **`EnrollmentsOfNonStudentsMigrationTests`** (2): V12 applied; run on inserted data it keeps only the student's enrollment.
  - Mutation-checked: no delete (4 fail), no flush before it (the race test fails), no role check on join (2 fail).

## 9.0e — GATE: publishing a draft whose deadline has passed

- **The problem (the phases 0-7 review, low, courses-assignments):** `setPublished` did not look at the deadline, so a draft past its deadline could be published and every submission to it was then refused (409 `SUBMISSION_DEADLINE_PASSED`): students saw an assignment they could never submit to.
- **The human's choice (asked in chat):** **refuse it unless the assignment already has submissions**, over always refusing and over allowing it and documenting. The exception: a teacher may unpublish an assignment with submissions (to hide a mistake, or to edit it under 9.0c) and must be able to show it again after its deadline, so students see it and their grades in the list. Its deadline cannot be moved instead without changing what the earlier submissions were judged against.
- **How:** in `AssignmentService.setPublished`, only on the draft-to-published change, after `PublishRules` (so a broken rubric is still reported first), under the existing row lock: `!deadline.isAfter(now())` and `!hasSubmissions` is the new 409 `ASSIGNMENT_DEADLINE_PASSED` ("The assignment's deadline has passed, so nobody could submit to it; move the deadline before publishing"). Nothing changes on a refusal.
  - **Exactly at the deadline is refused:** a submission is accepted at the deadline instant and refused after it (5.1c), so publishing at it leaves no time to submit. The same `Clock` as everywhere else (microseconds).
  - **The fix is a `PUT` with a later deadline** (3.3a requires it to be in the future), then publish.
  - **Not checked:** re-publishing an already-published assignment (the 3.3c no-op) and unpublishing, as for the rubric rules.
- **Tests (backend 2746 -> 2753, +7; ai-service and frontend unchanged and passing), `RealHttpPublishRulesTests`** (uses the `MutableClock`): refused after the deadline (owner and admin, nothing changed); refused exactly at it, accepted one microsecond before; the rubric rule is reported first; an assignment with a submission is published again after its deadline and the student sees it; a published one stays published past its deadline and can be hidden; moving the deadline forward with `PUT` makes the draft publishable. Mutation-checked: no check (4 fail), ignoring submissions (1 fails), `isBefore` for the boundary (1 fails).

## 9.0f — the losing request of a double click

- **The problem (the phases 0-7 review, low, hints):** two requests for the same level at once both call the AI service (7.2a). Only the insert path handled the race (the second insert hits `LEVEL_KEY` and returns the stored row). If the second request's own answer was a fallback, it returned that fallback (200, not stored), and if its call failed, a 503, although the first had stored a real hint for the level: the student saw a worse answer, or an error, for a hint they had.
- **Fix, as the suggestion said:** on a fallback or an `AiServiceException`, `requestNext` looks the level up once (`storedMeanwhile`: `findBySubmissionIdAndHintLevel`, its own short read-only transaction, after the AI call, so 7.1a's no-transaction rule still holds) and returns the stored row as a 200 with `fallback: false`. Only with no row is it the fallback (200) or the 503, as before. The failure is still logged either way (the AI service did fail).
  - **Same level only:** the lookup is for the level this request computed. A lower stored level never stands in for it (tested), and a higher one cannot exist (levels are given in order).
  - **Not a 201:** this request stored nothing, as for the insert-path loser.
- **Tests (backend 2753 -> 2763, +10; ai-service and frontend unchanged and passing), `RealHttpHintRequestTests`:** the race is played without threads. The mocked AI call inserts the winner's row before answering, which is exactly the interleaving.
  - a fallback loser gets the stored hint;
  - a failed loser gets it, for each of the eight `failures()`;
  - a fallback at level 2 with only level 1 stored is still the fallback.
  - The existing fallback and 503 tests (no stored row) are unchanged and still pass.
  - Mutation-checked: reverting the failure path fails 8, reverting the fallback path fails 1.

## 9.1a — teacher course list + create-course form

- **`/courses` by role** (one route, the home of students and teachers, 8.4a): a teacher gets their courses and the create form; **an admin gets the same list (every course, `GET /courses` picks it by role, 3.1b) without the form** (`POST /courses` is teachers only, 3.1a). The admin's menu already links to Courses (8.5a) and the list is the same component, so the admin part costs nothing; say if admins should see something else. **A student still sees only the heading**: their list, the join form and the empty state are 9.1b's.
- **The enrollment code** (the row: "prominently with a copy action"): in its own column, in the monospace `<code>` style at 24 px with letter spacing (`.enroll-code` in `index.css`, the first page-specific style), plus a **`CopyButton`** (`src/components/`, reusable): `navigator.clipboard.writeText`, accessible name "Copy the enrollment code of <title>", result in a `role="status"` next to it: "Copied", or "Could not copy: select the code and copy it" when the browser refuses (no secure context, permission denied). The code always stays on screen. No library.
- **Create form** (`CreateCourseForm`, the 8.3a form conventions): title required after trimming, at most 255; description optional, a `<textarea>` (`TextField` gained `rows`), at most 5000, sent as typed or `null` when blank (the backend treats blank as none anyway). A `VALIDATION_FAILED` goes on its fields; anything else is one alert above the button; the button is disabled while sending. **On success** the form empties and a "New course" region says `"<title>" is created` (`role="status"`) with its code and a Copy button, and the list reloads (the new course is last, oldest first, so the count and page total change).
- **Course titles are not links yet:** the course page is 9.2a's.
- **Shared pager:** 8.5b's Previous / Next and `pageNumberOf` moved to `src/components/Pager.tsx` and `pagination.ts`, now used by both list pages; `AdminUsersPage`'s 14 tests pass unchanged.
- **Checked in a real browser** (the desktop app's pane; dev-profile backend from `backend/.env`, compose database, Vite), signed in as the seeded teacher:
  - the list showed "Introduction to Programming" with `DEVSEED` large and a Copy button;
  - **Copy showed the fallback message: the pane itself refuses clipboard writes** (`NotAllowedError: Write permission denied`, also from page script with focus), so the success path is covered by the tests only (user-event's clipboard);
  - creating "  Browser check 9.1a  " sent a CORS preflight (200), `POST /courses` 201 with the trimmed title, showed the new code and reloaded the list (2 courses);
  - the test course was deleted afterwards (`DELETE /courses/{id}` 204), and the servers and the database stopped.
- **Tests (frontend 192 -> 209, +17; backend and ai-service unchanged and passing), `CoursesPage.test.tsx`:**
  - the teacher's list with codes and Copy buttons, page 0 with the cookie;
  - loading, a failed load, the empty state (with the form, no table or pager), next and previous page in the URL;
  - Copy puts the code on the clipboard and says "Copied"; a refused clipboard says to copy by hand and the code stays;
  - create sends the trimmed title and `null`, shows the new code with Copy, empties the form and reloads; a description is sent as typed from a textarea;
  - the page's own checks (blank, 256-character title, 5001-character description; nothing sent), exactly 255 accepted;
  - the backend's field errors on their fields, any other refusal above the button with the text kept, the button disabled while sending;
  - an admin sees every course with codes and no form, and the admin's empty state;
  - a student asks for nothing yet.
  - Mutation-checked: no code in the rows (4 fail), no reload after create (1), the form for admins (2), the title not trimmed (1).
