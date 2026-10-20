# Decisions / Assumptions — Phase 5 (`docs/tasks/tasks-phase-05.md`)

Historical, append-only. One entry per commit where a non-obvious call was made — a gap in the task spec, a gate resolution, a formatter/tool choice, a bug found and fixed. Not read every session — see the note in root `CLAUDE.md`. Load this only when auditing a specific Phase 5 decision.

## 5.1a — `POST /assignments/{id}/submissions` with the code as text

- **Body `{sourceCode}` only; the language is the assignment's.** The assignment has exactly one language and it is locked once anyone submits, so asking the client to repeat it adds nothing but a way to be wrong; a `language` sent anyway is ignored (tested), like the ignored fields of the course and assignment bodies. 5.1c's "language matches" then applies to 5.1b's file extension. Flagged: if you want the client to name the language and get a 400 on a mismatch, that is a small change in 5.1c.
- **Students only (`hasRole('STUDENT')`); teachers and admins get 403**, even the course owner. The row says "enrolled student"; a teacher trying out their own assignment would need a student account (or a later "test run" feature). Flagged.
- **Not visible = the 404 `ASSIGNMENT_NOT_FOUND` of a missing id, byte for byte** (not enrolled, a draft, another course, a student who left): the same rule as reading the assignment (3.3b), so the "published" part of 5.1c is already enforced here, as a 404. 5.1c may keep it so.
- **Blank code is a 400 `VALIDATION_FAILED`** (`@NotBlank`), so an empty paste does not cost an attempt. Otherwise the code is stored byte for byte (CRLF, tabs, trailing spaces, non-ASCII: tested), not normalised like test inputs (4.7e): compilers and Python accept CRLF source.
- **The assignment is read under `FOR KEY SHARE`** (`AssignmentRepository.findByIdForKeyShare`, native for the same reason as 3.3c's `FOR UPDATE`). Without it, a submission could read the language just before a teacher's change commits and store the old one, after which the language is locked to the new one: the submission would be graded as a language its assignment no longer has. The lock is the one the foreign key takes at the insert anyway, only taken at the read; it conflicts with the teacher's `FOR UPDATE` both ways and not with other submissions. The course access check runs before the lock (needs only the course id, as `lockManageable`), the published check under it, so an unpublish in flight is also seen.
- **`attempt_no` = max + 1 in the same transaction, not yet safe against the same student submitting twice at once**: both transactions hold only shared locks, compute the same number, and the second insert hits `UNIQUE (assignment_id, student_id, attempt_no)` (a 500 today). The row for that is 5.1c ("compute `attempt_no` inside a transaction so two simultaneous submits can't get the same number"), done next; not duplicated here.
- **No size cap yet** (5.1c's "max source size"), so until then the body size is bounded only by the server's defaults.
- **Answer: `{id, attemptNo, status, submittedAt}`**, no `Location` header (`GET /submissions/{id}` is 5.5a). `submittedAt` is the database's `NOW()` (transaction start), read back after the insert.
- **`RealHttpTestBase.emptyTables` clears `submissions` first** now that it has a repository; the tests that already deleted their own SQL-inserted submissions keep doing so (harmless).
- **Tests (+16, backend 1740 -> 1756; ai-service and frontend unchanged and passing): `RealHttpSubmitTests` (11) and one role-matrix row (5 checks).** accepted with the raw key set, attempts per student and per assignment, language ignored in the body, the language lock through the API, every not-visible case byte-identical to a missing id, leaving the course, roles, blank/missing/not-JSON bodies, and three races against held locks (language change in flight → new language stored; unpublish in flight → 404; a classmate's submission in flight does not block). Mutation-checked: a plain `findById` instead of the lock fails the two teacher races; dropping the published filter fails the not-visible and unpublish tests.
- **Slip during the mutation check, fixed:** restoring a mutated file with `git checkout --` also discarded the commit's own uncommitted change to that file (`lockVisibleForSubmission`); it was re-added and the tests re-run. Mutations are now restored from a copy taken before them.

## 5.1b — The same endpoint accepts one uploaded source file (multipart)

- **Same URL, a second handler chosen by `consumes`**: `multipart/form-data` with the file in the part `file`; the JSON handler now declares `consumes = application/json` (a body of any other type, or none, was a 415 before too and still is; tested). One method + pattern for the role matrix, so no new row there; `RealHttpSubmitFileTests` covers the roles for uploads.
- **Exactly one file in `file`, else 400 `ONE_SOURCE_FILE_EXPECTED`** (no file, two files, the file under another name, or `file` sent as a plain field). Other parts are ignored, like extra JSON keys (a `language` part does not change the language; tested).
- **Only the extension is checked, as the row says, compared ignoring case** (`MAIN.JAVA`, `main.PY` pass: Windows names are case-insensitive), from the part after the last `/` or `\` and the last `.` (`dir/Main.java`, `my.main.Java` pass; `main.py.txt`, `main.`, `main`, an empty name fail). The expected extension is that of the language's `source-file-name` in the config (`LanguageSpec.sourceFileExtension()`), so no language is named in code and a new language needs nothing. **The file's own name is not kept** (no column for it): a Java file named `Solution.java` is accepted and still built as `Main.java`, so a class not called `Main` fails at compile time (the README's convention). Flagged: requiring the exact file name for compiled languages would be a small change.
- **The extension is checked against the language read under `lockVisibleForSubmission`**, after the visibility check: an assignment the student may not see is the same 404 whatever the file's name (a wrong extension would otherwise tell an outsider the language), and a teacher's language change in flight is waited for and then applies (tested both; mutation: checking against an unlocked read before the lock fails both). A language no longer in the config is 400 `UNSUPPORTED_LANGUAGE` (nothing to compare with; the text path still accepts it until 5.1c).
- **The file's checks run first (one file, size, text, blank), as the JSON body's validation does**, but after `@PreAuthorize` (they are in the method body), so a teacher's empty upload is still a 403.
- **Size cap `SubmissionService.SOURCE_FILE_MAX_BYTES` = 128 KiB, in bytes — my placeholder, for you to set.** Above the 50000-character starter-code cap so a student can upload the starter unchanged; a file of exactly the cap passes, one byte more is 413 `SOURCE_FILE_TOO_LARGE`, and two-byte characters count as two (tested). 5.1c's "max source size" for the text body can reuse the same constant.
- **The server's own multipart limit is a hard bound above the cap** (`spring.servlet.multipart.max-file-size`/`max-request-size` 1MB), answered with the same `SOURCE_FILE_TOO_LARGE` (override of `handleMaxUploadSizeExceededException`; the only upload in the API is this one). It is enforced while the request is parsed, before method security, so a teacher's 1.5 MB upload is 413 rather than 403; anonymous is still 401 from the filter chain.
- **"Files are not stored on disk":** Spring Boot's default `file-size-threshold` is 0, which lets the servlet container write every part to a temporary file. It is set to 2MB, above the largest request, so every part stays in memory; `theServersUploadLimitsKeepEveryUploadInMemoryAndAboveTheSubmissionCap` pins the relation.
- **Strict UTF-8, NUL refused (400 `SOURCE_FILE_NOT_TEXT`)**: a malformed byte is refused rather than replaced with U+FFFD (that would silently change the code), and Postgres `TEXT` cannot store U+0000. Latin-1, UTF-16 and a broken sequence are tested. **A leading UTF-8 BOM is dropped** (Windows editors add one, and `javac` rejects it as an illegal character); otherwise the text is stored byte for byte, CRLF included, as for text. Blank (also a BOM alone) is 400 `SOURCE_FILE_EMPTY` and costs no attempt.
- **Tests (+18, backend 1756 -> 1774; ai-service and frontend unchanged and passing): `RealHttpSubmitFileTests`.** Mutation-checked: no extension check; the extension checked against an unlocked read before the visibility check; lenient decoding; no `MaxUploadSizeExceededException` override; no own size check — each fails its test(s). Mutations were restored from copies taken before them (the 5.1a lesson).

## 5.1c — Submission validation (deadline, attempts, size, language) and safe attempt numbers

- **One student's submissions are serialised by locking their enrollment row (`FOR UPDATE`)**, taken after the assignment's `FOR KEY SHARE` (`EnrollmentRepository.lockByCourseIdAndStudentId`). With it, `max + 1` is exact and so is the attempt-limit count (two requests at the last attempt cannot both pass). Considered: retrying on the unique constraint (the 3.1a pattern) does not make the limit check exact; an advisory lock on a hash of (assignment, student) works but is invisible in the schema; the user row would also block that user's unrelated writes. The enrollment row is exactly "this student in this course", only students submit, and it closes a second race too: a student leaving in flight is waited for and then refused with the 404 (tested). Different students never wait for each other (tested). Lock order is assignment then enrollment; course deletion takes enrollments first but no assignment lock that conflicts, so no cycle. Mutation: without the lock, both bursts get 500s from the unique constraint (the 5.1a gap) and the waiting tests fail.
- **Deadline: refused when the request arrived strictly after it; exactly at it is accepted** ("after the deadline are rejected"), to the microsecond the database keeps (tested at the instant and 1 µs later, for text and file). 409 `SUBMISSION_DEADLINE_PASSED`. 409 like the other "the assignment's state does not allow it" refusals (`ASSIGNMENT_PUBLISHED`, the locks); a 403 would read as a permission problem.
- **"Arrived" is the application clock read at the start of the service call, and that instant is stored as `submitted_at`** (no longer the database's `NOW()`; the column default stays for SQL inserts such as the seed). So the stored time and the decision always agree, and waiting for a teacher's lock or the student's own earlier submission never makes a request late. The cost: two simultaneous attempts may have `submitted_at` in the opposite order to `attempt_no` (microseconds apart). The deadline itself is read under the assignment lock, so a teacher's change in flight is waited for and applies (tested).
- **Order of the checks:** body (size, text) → visibility 404 → enrollment → deadline → attempts → language → file extension. Nothing about an unseen assignment is told (a late submission to a draft is still the 404, tested); a late student is told it is late, not that their file has the wrong extension or that attempts are used up (tested).
- **Attempt limit: every stored submission counts, whatever its status, including a future `ERROR`.** 409 `ATTEMPT_LIMIT_REACHED`. Flagged: if a platform failure (`ERROR`, 5.2b) should give the attempt back, that is a change in the count (`attempt_no` itself must stay unique, so the numbering would then differ from the count). A raised limit applies at once (tested).
- **Max source size: the 5.1b constant now covers text too, renamed `SOURCE_MAX_BYTES` (still 128 KiB, still a placeholder).** Bytes of UTF-8, not characters (tested). Not bounded before parsing: see the new 5.1c entry in `docs/suggestions.md`.
- **`SOURCE_FILE_TOO_LARGE` → `SOURCE_TOO_LARGE` and `SOURCE_FILE_NOT_TEXT` → `SOURCE_NOT_TEXT`** (and their exception classes): the same refusal now answers both request kinds, so the client branches on one code. Renamed rather than adding two more, because the codes are a day old and the frontend does not use them yet (9.4b). The file-only codes (`ONE_SOURCE_FILE_EXPECTED`, `SOURCE_FILE_EMPTY`, `SOURCE_FILE_EXTENSION_MISMATCH`) keep their names; blank text stays the `VALIDATION_FAILED` of 5.1a.
- **"Language matches":** text submissions are always in the assignment's language (5.1a) and files are checked by extension (5.1b); what was left is a language removed from the config after the assignment was made, now 400 `UNSUPPORTED_LANGUAGE` for text too (it could only fail in the sandbox later).
- **The 5.1b suggestion, done here as the human asked:** confirmed first by mutation (without the check a `\u0000` in `sourceCode` is a 500 `INTERNAL_ERROR`); text is now refused with 400 `SOURCE_NOT_TEXT` for a NUL, and also for an unpaired surrogate (a JSON `\ud800` escape decodes to one, and the JDBC driver would store it as `?`, silently changing the code); a correct pair, escaped or raw, is stored intact (tested).
- **Published** was already enforced in 5.1a as the 404 of a missing id; kept.
- **Tests (+17, backend 1774 -> 1791; ai-service and frontend unchanged and passing): `RealHttpSubmitRulesTests`,** with a `MutableClock` (test package, the primary `Clock` of that context only) for exact instants and its own thread pool so the 8-request bursts are simultaneous on a 2-core CI runner. Mutation-checked: no enrollment lock; refused at exactly the deadline; attempt limit off by one; no storability check on text; no size check on text: each fails its tests. A first version of the lock mutation (`&& false` on the empty check) still took the lock and so only failed the leave test; redone by removing the lock.
- **CI fix on PR #99 (second commit):** the first push failed CI because `RealHttpErrorShapeTests` could not load its context: `FATAL: sorry, too many clients already` on the shared Postgres. `RealHttpSubmitRulesTests`' own `@Import(MutableClock.Config.class)` was an 11th cached test context (each holds a Hikari pool). Moving the import to `RealHttpTestBase` was not enough: it split the base context from `RealHttpAuthTests` (same configuration, not a subclass), still 11. **`MutableClock.Config` is now imported by `TestcontainersConfiguration`**, which every integration test context already has, so the contexts are exactly those of 5.1b (10 pools, counted from the Hikari start lines of the local runs before and after). The headroom problem itself is in `docs/suggestions.md` (5.1c).

## 5.1d — A submission that ends in `ERROR` gives the attempt back

- **The human's decision after 5.1c** (asked in PR #99): a platform failure (`ERROR`, set by the worker in 5.2b) is never the student's fault, so it does not use up one of `max_attempts`. The human also chose option 1 for the test connection limit and approved bounding JSON bodies; those became rows 5.1e and 5.1f, added in this commit (one PR per row, as with 4.7e).
- **Only the limit's count changes.** `SubmissionRepository.countAttemptsUsed` counts the student's submissions to the assignment whose status is not `ERROR`; the limit is reached when that count is `>= max_attempts`. `attempt_no` is still max + 1 over every submission (the unique constraint needs it), so after an error the next attempt is numbered 3 although it is the second that counts; the failed submission is kept, with its code (5.2b). Both reads run under the enrollment lock of 5.1c, so a burst after an error accepts exactly the attempts left (tested).
- **`QUEUED`, `RUNNING` and `GRADED` all count** (tested): a submission still waiting may yet be graded, and counting it only once it is graded would let a student queue unlimited attempts before the first one finishes.
- **Not handled, because v1 has no path to it:** an `ERROR` submission that is later graded would make the count exceed the limit; re-grading is out of scope (root `CLAUDE.md`), and 5.2c re-queues only `QUEUED`/`RUNNING`.
- **Tests (+4, backend 1791 -> 1795; ai-service and frontend unchanged and passing)** in `RealHttpSubmitRulesTests`, with statuses set by SQL as the worker will set them. Mutation-checked: the 5.1c rule (every submission counts) fails two; excluding `GRADED` instead of `ERROR` fails three.

## 5.1e — The shared test Postgres runs with `max_connections=300`

- **The human's choice (option 1) for the 5.1c suggestion.** `SharedPostgres` starts the container with `postgres -c fsync=off -c max_connections=300`: Testcontainers' own default command is `postgres -c fsync=off` (read from the 2.0.5 class), and `withCommand` replaces it, so `fsync=off` is repeated to keep the speed it gives. The limit is the named constant `SharedPostgres.MAX_CONNECTIONS`.
- **Why 300 is enough for now:** the run has 10 cached Spring contexts, each holding a Hikari pool of up to 10 connections until the JVM ends, plus one short-lived admin connection per new database; 300 leaves room for about thirty contexts. Hikari's pool size and the context cache size are unchanged (options 2 and 3, not chosen).
- **Tests (+2, backend 1795 -> 1797; ai-service and frontend unchanged and passing): `SharedPostgresTests`, without a Spring context** (so it adds no pool of its own): `show max_connections` is 300 and `show fsync` still `off`; and 120 connections open at once (over the old 100 on their own, while leaving the other contexts' pools room). Mutation-checked: without the command both fail, the second with exactly the CI error of PR #99, `FATAL: sorry, too many clients already`.

## 5.1f — A request body is bounded before it is parsed

- **One servlet filter, `security/RequestBodyLimitFilter`, for every non-multipart body (a superset of "every JSON endpoint")**, registered by `SecurityConfig` at `Ordered.HIGHEST_PRECEDENCE + 1`: after the character-encoding filter, ahead of Spring's form filter (-9900, which reads `PUT`/`PATCH`/`DELETE` form bodies) and the security chain (-100). So the bound holds for anonymous callers too, and an oversized body is 413 before the 401/403 a caller would otherwise get (as the multipart bound already was, 5.1b). Multipart bodies are left to `spring.servlet.multipart` (same test as Spring's resolver: content type starting with `multipart/`), so the upload answer stays `SOURCE_TOO_LARGE`.
- **Maximum `RequestBodyLimitFilter.MAX_BODY_BYTES` = 2 MiB, a placeholder.** The largest valid body is a test case (two texts of `TestCaseRequest.TEXT_MAX` 100000 characters plus a 255-character name); a JSON client may escape every character as `\uXXXX`, 6 bytes, the most any Java `char` can take, which gives about 1.2 MB. Assignments (~421 KB), courses and submissions (`SOURCE_MAX_BYTES` bytes, at most as many characters, ~786 KB) are below that. Arbitrary whitespace between JSON tokens is not counted as "valid".
- **New generic `ErrorCode.REQUEST_TOO_LARGE` (413, "The request body is too large")**, and `ErrorCode.forStatus(413)` now maps to it (it was `REQUEST_REJECTED`), so a 413 the container itself produces gets the same code. The submission endpoint can therefore answer two "too large" codes: `SOURCE_TOO_LARGE` for code over 128 KiB in a body up to 2 MiB, `REQUEST_TOO_LARGE` above 2 MiB; the frontend should treat both as "too large".
- **How it bounds:** a declared `Content-Length` over the maximum is refused without reading a byte (Tomcat never hands the application more than the declared length, so a declared length within the maximum passes untouched). A body of unknown length (chunked) is read up to the maximum + 1 byte; over is refused, otherwise the request goes on wrapped with the bytes in memory (`getInputStream`, `getReader` in the request's encoding, `getContentLength` = the real length). At most 2 MiB is held per such request, which Jackson would hold anyway.
- **A refusal sends `Connection: close` and is flushed before the filter returns**, and `JsonErrorWriter` now writes a declared `Content-Length` (bytes through the output stream instead of the writer). Found while testing: Tomcat swallows at most 2 MB of an unread body and then closes the connection, and without the flush the buffered 413 was never sent: the JDK client saw "header parser received no bytes". With the declared length the answer is framed even though it is flushed before the response ends.
- **Known limit, not handled:** a client that keeps sending a large body after the 413 may see its write fail (connection reset) before it reads the answer; that is how HTTP/1.1 servers refuse early, and a client using `Expect: 100-continue` avoids it. The JDK 17 `HttpClient`'s own `expectContinue` did not cope with a final response in place of `100`, so the tests send only the headers over a raw socket. CORS headers are not on the 413 (the filter runs before the CORS filter); no valid request from the frontend can reach the bound.
- **Tests (+14, backend 1797 -> 1811; ai-service and frontend unchanged and passing):** `RequestBodyLimitFilterTests` (7, no Spring context: declared over/within, chunked over/within byte for byte, reading as text, multipart passes, and the maximum against the largest valid bodies computed from the DTO constants with every character escaped) and `RealHttpRequestBodyLimitTests` (7: a declared 2 MiB + 1 and 100 GiB body refused with only the headers sent, chunked over refused, before authentication and the role rule, chunked within reaches the controller intact, a body of exactly the maximum reaches the controller, and the largest test case and submission with every character `\u`-escaped are accepted and stored exactly); `ErrorPiecesTests` updated for the 413 mapping. Mutation-checked: no flush, the filter after the security chain, no bound on chunked bodies, multipart not exempt: each fails at least one test.

## 5.2a — Bounded worker pool that picks up `QUEUED` submissions

- **The human's decision (asked before coding): the database is the queue of record.** `service/SubmissionDispatcher` owns a `ThreadPoolTaskExecutor` with `submissions.worker.pool-size` threads (core = max) and an in-memory queue of `submissions.worker.queue-capacity`. A submission is offered once its insert commits (`SubmissionQueuedEvent`, published in `SubmissionService.accept`, handled by a `@TransactionalEventListener`, i.e. after commit, so the worker never looks for a row it cannot see yet; a refused submission is never offered). When the pool and its queue are full the offer is refused and the row simply stays `QUEUED`: the student gets the usual 202 and nothing is lost. The rejected alternatives were workers pulling straight from the table (no in-memory queue, which drops the row's "queue capacity") and a 503 when full.
- **How a waiting row gets its turn:** every time a worker takes a task, which frees one place in the in-memory queue, it first offers the oldest `QUEUED` rows (`SubmissionRepository.findQueuedIds`, by id = arrival, a window of pool + queue rows) that are not handed over, until one is refused. An in-process set of handed-over ids stops a row from being offered twice at once. Refilling when a task *ends* would not work: the finishing thread still counts as busy, so with a full queue the offer is refused. **This is also why `queue-capacity` must be at least 1** (with no queue there is never a "take" to refill on); both values must be at least 1 and have no default in code (`SubmissionWorkerProperties`, startup fails otherwise).
- **The human's decision: picking up includes the claim** (5.2b keeps `GRADED`/`ERROR`, `finished_at` and "any crash ends in `ERROR`"). `SubmissionWorker.work` runs `SubmissionRepository.claim`: one native `UPDATE ... SET status = 'RUNNING', started_at = :now WHERE id = :id AND status = 'QUEUED'` (its own transaction), so a submission is taken by one worker at most and a row that is no longer `QUEUED` or gone is left alone. `started_at` is the application `Clock`, microseconds, as `submitted_at`. Then it calls the `SubmissionProcessor` (new interface, no implementation yet; `ObjectProvider.ifAvailable`), so for now a claimed submission stays `RUNNING`. A `RuntimeException` from the work is logged and never stops the thread; what it does to the submission is 5.2b's.
- **Placeholders, in `application.yml`: pool size 2, queue capacity 100.** Each submission runs its tests one after the other in containers of one CPU each, so the pool size is about the number of CPUs grading may take.
- **The pool is not a Spring bean**, so it cannot replace Spring Boot's `applicationTaskExecutor` (which a bean of type `Executor` would). It is shut down with the context without waiting; what was waiting stays `QUEUED` and what was taken stays `RUNNING`, for 5.2c. Nothing is picked up at startup (the 4.3e sandbox sweep must come first; recovery is 5.2c), although an old `QUEUED` row is picked up by the first refill after a new submission arrives. One backend instance is assumed (as for the sweep).
- **Effect on the other tests:** every test context now has a worker, which claims each submitted row (`QUEUED` → `RUNNING`) shortly after the 202, with nothing else happening. Two assertions that the stored status is `QUEUED` (`RealHttpSubmitTests`, `RealHttpSubmitFileTests`) now accept `QUEUED` or `RUNNING`; the attempt-limit tests that set statuses by SQL are unaffected (`RUNNING` and `QUEUED` both count, and the claim only matches `QUEUED`). Once the worker grades (5.3a), every test that submits would also start sandbox runs: see `docs/suggestions.md`.
- **Tests (+11, backend 1811 -> 1822; ai-service and frontend unchanged and passing):** `SubmissionDispatcherTests` (6, no Spring, an in-memory stand-in for the `QUEUED` rows: a burst beyond pool + queue is refused into the table and all graded once with at most pool-size at a time; oldest first; a row nobody offered is picked up when the next one starts; offered twice = worked once; a crash does not stop the workers; property validation) and `RealHttpSubmissionWorkerTests` (5, real database, pool 1 / queue 1 and a processor that holds each claim: a burst runs one at a time in order while the rest stays `QUEUED` with no `started_at`; `started_at` is the app clock; a `RUNNING`, finished or missing row is never taken again; nothing is taken while the inserting transaction is still open, and it is taken after the commit; a refused submission is never offered). Mutation-checked: no refill, no double-offer guard, a plain `@EventListener` (offer before commit: the claim cannot see the row, and it is never taken), a claim that ignores the status, a pool one larger: each fails at least one test (the before-commit one only through the held-transaction test, which was added for it).

## 5.2b — Status transitions `QUEUED` → `RUNNING` → `GRADED` / `ERROR`, with `started_at` / `finished_at`

- **The worker owns every status change; the processor only does the work.** `SubmissionWorker.work` claims (5.2a, `RUNNING` + `started_at`), calls `SubmissionProcessor.process`, then writes `GRADED` if it returned or `ERROR` if it threw, both with `finished_at` (`SubmissionRepository.finish`). Keeping the transitions in one place means no grading code can forget to end a submission, and a processor that commits its `test_results` and grade before returning makes `GRADED` appear only once they exist. A crash between those commits and the `GRADED` update leaves the row `RUNNING` with partial results, which 5.2c re-runs (its row already says re-running replaces previous partial `test_results`). The alternative, a processor that writes the final status in the same transaction as the grade, would split the status logic between two classes; not taken.
- **"Any unexpected exception" is taken literally:** the worker catches `Exception | Error`, so a checked exception thrown sneakily ends in `ERROR` too, and so does an `Error` (e.g. `StackOverflowError` in grading code). An `Error` is rethrown after the row is marked, so it is not swallowed: the pool's thread dies and `ThreadPoolExecutor` replaces it (tested with a pool of one). A sandbox failure (4.6a's `SandboxFailedException`) is just an exception here, so it ends in `ERROR` with nothing graded, as 4.6a intended.
- **`finish` is a conditional JPQL update that only matches `RUNNING`** and sets only `status` and `finished_at`: the source code, language, attempt number and timestamps are never rewritten, so a crash never loses the student's code, and a row that is gone or already finished is left alone (returns 0). The status value is typed (`SubmissionStatus`), unlike the native `claim`. `finished_at` is the application `Clock`, microseconds, as `started_at` and `submitted_at`.
- **Without a processor bean a claimed submission stays `RUNNING`, not `ERROR`.** There is no production implementation until 5.3a; marking those `ERROR` would give every test context's submissions their attempt back (5.1d) and make the attempt-limit tests depend on the worker's timing. The existing relaxed assertions (`QUEUED` or `RUNNING`) keep working.
- **A database failure while claiming or finishing is not retried:** the dispatcher logs it and the row stays as it was (`QUEUED` or `RUNNING`) until 5.2c's startup recovery. Retrying against a failing database inside the worker would only hold the thread.
- **No reason is stored for an `ERROR`:** the schema has no column for it and no task asks for one; the cause goes to the log with the submission id. Whether students or teachers should see a reason is a question for the result views (5.5).
- **Tests (+10, backend 1822 -> 1832; ai-service and frontend unchanged and passing):** `SubmissionWorkerTests` (6, mocked repository and a fixed clock: processed = `GRADED` after the processor, a runtime exception, a sneaky checked exception and an `Error` (rethrown) = `ERROR` and never `GRADED`, a lost claim = nothing, no processor = stays `RUNNING`) and `RealHttpSubmissionWorkerTests` (+4: `finished_at` from the clock on `GRADED`; an exception ends in `ERROR` with source, language, attempt number, `submitted_at` and `started_at` unchanged, and the worker carries on while the `ERROR` gives the only attempt back; an `Error` ends in `ERROR` and the replaced thread grades the next one; `finish` from every status to `GRADED`/`ERROR` changes only `RUNNING` rows). Two 5.2a tests now expect `GRADED` once let go. Mutation-checked: `finish` without its `RUNNING` condition, an exception ending `GRADED`, an `Error` not rethrown, only `RuntimeException` caught, `GRADED` never written, `finished_at` from the system clock: each fails at least one test.

## 5.2c — Startup recovery: re-queue submissions left `QUEUED` or `RUNNING` after a restart

- **Two steps at two startup moments (`service/SubmissionRecovery`).** Step one, as a `SmartInitializingSingleton`, resets the database: every `RUNNING` row is `QUEUED` again with `started_at` null. It runs while singletons are being finished, before the embedded server's connectors start (`finishRefresh`). So no request can have created and claimed a submission yet, and every `RUNNING` row really is from the previous process. Step two, as an `ApplicationRunner`, offers the oldest `QUEUED` rows to the pool (`SubmissionDispatcher.pickUpQueued`); the rest follow as workers take tasks, the 5.2a refill. It runs after every `SmartInitializingSingleton`, so after the 4.3e sandbox sweep, which removes every sandbox container and must never see a run of this process. The ordering between the two `SmartInitializingSingleton`s (sweep and step one) does not matter: step one starts nothing. The rejected alternative was one `ApplicationRunner` doing both steps. By then the server is already taking requests, so a new submission could be claimed and then reset by the recovery, and graded twice; the only way out would be comparing `started_at` with the process start, which the test `MutableClock` makes unreliable.
- **"Re-running replaces partial `test_results`" is done by the recovery, not by the grader:** step one deletes the `test_results` of the `RUNNING` rows in the same transaction as the reset (delete first, while they are still `RUNNING`). So no `QUEUED` row ever has results, and 5.3a's processor can insert without first deleting. QUEUED rows are not touched: results are only ever written while a submission is `RUNNING`.
- **Only `test_results` are cleared, not `grades` / `criterion_scores`.** The row names only `test_results`, and `criterion_scores` will also hold a teacher's `MANUAL` scores (5.4d), which a restart must never delete. A crash after the grade commit but before the `GRADED` update leaves a grade row (unique per submission), so **5.4's scoring must replace its own rows rather than insert** (noted in `backend/CLAUDE.md`).
- **Idempotent:** a second step one finds no `RUNNING` row from before (and must never be called while the app runs, since it would take back this process's own claims). A second step two offers nothing twice, because the dispatcher's handed-over set stops it.
- **A submission is re-run at every restart until it finishes.** Grading runs in the sandbox, so student code cannot crash the backend, and a crash loop on one submission is not expected; no attempt counter was added (flagged in `docs/suggestions.md`).
- **Tests (+6, backend 1832 -> 1838; ai-service and frontend unchanged and passing):** `SubmissionRecoveryTests` has 3 tests, against mocks. The steps are a `SmartInitializingSingleton` and an `ApplicationRunner`. Step one deletes, then re-queues, then commits in one transaction and offers nothing. Step two offers and changes no row. `RealHttpSubmissionWorkerTests` gets 3 more, calling the two hooks as Spring does:
  - A `RUNNING` row becomes `QUEUED` without `started_at` or its results; source, attempt number and `submitted_at` are kept; `QUEUED`, `GRADED` (with its results) and `ERROR` rows are untouched; step one offers nothing.
  - After both steps, two left-`QUEUED` and two left-`RUNNING` rows are all graded oldest first through a pool of one.
  - Recovering twice grades the submission once.

  A real restart against the same database is 5.6d's test. Mutation-checked; each mutant failed at least one test:
  - partial results kept
  - re-queue before the delete
  - `started_at` kept
  - every non-`QUEUED` status re-queued
  - results of all submissions deleted
  - step two offers nothing
  - step one no longer a `SmartInitializingSingleton`

## 5.3a — Worker runs the assignment's tests through the Phase 4 runner and persists `test_results`

- **`service/SubmissionGrader` is the application's `SubmissionProcessor`.** It reads the submission, the assignment's test cases in run order (position, then id), the assignment's limits and the language's `source-file-name`, then calls `SubmissionTestRunner.runAll` (4.6a). That runs the build once, one container per test, and compares in the backend. It stores one `test_results` row per test with a single `saveAll`, so a submission has all of its results or none. The sandbox runs outside any transaction (CLAUDE.md: no long work in an open transaction). Public and hidden tests take exactly the same path; only 5.5a's view differs.
- **The status stays the worker's (5.2b).** The grader returns or throws. A `SandboxFailedException` (any `SANDBOX_ERROR`) is thrown by `runAll` before anything is stored, so the submission ends in `ERROR` with no results, as 4.6a intended. A language removed from the config after the submission is accepted throws `IllegalStateException`, also `ERROR`: the platform's fault, so the attempt is given back (5.1d).
- **A new `entity/TestResult`** (and `TestResultRepository`) maps V8 with explicit columns. Its `status` is the sandbox's `TestOutcome` stored by name: 4.6a defined its values as exactly the column's, and a second enum in `entity` would need a mapping that could drift. Its `toString` shows lengths only.
- **NUL characters are replaced by U+FFFD before storing** `actual_output` and `stderr`. Postgres `TEXT` refuses U+0000, so a student program that prints one would otherwise make the insert fail and the submission `ERROR`: the attempt would be given back, and the "grade" would hinge on a storage detail. The comparison was already made on the output as printed, so the verdict is unaffected. Found while writing the tests; `RealHttpGradingTests` checks it against real Postgres.
- **No tests means nothing is run or stored, and the submission is `GRADED`.** A published assignment always has tests (3.4b), but the 5.1 test fixtures publish by SQL without any, and starting a container to build code that no test runs would cost time for nothing. Scoring such a submission (zero) is 5.4a's. A submission deleted while claimed is left alone (the worker's `finish` then matches nothing).
- **Compile errors are stored exactly as the runner returns them:** each test gets `COMPILE_ERROR` with the compiler's output in `actual_output`/`stderr`. Whether to store it once, or as NULL program output (V8's comment), is 5.3b's row.
- **No test-only switch for grading (the 5.2a suggestion, "to be decided with 5.3a").** Every test context now has the real grader. I checked the existing suite: no test submits to an assignment that has test cases (the body-limit tests' test cases belong to a draft), so grading there is a database read and no container starts. The attempt tests that set statuses by SQL stay correct, because `finish` only matches `RUNNING` (5.2b). Two status asserts now also accept `GRADED`. The worker tests make their holding processor `@Primary`, and `emptyTables` clears `test_results` first. The suggestion stays open, with this finding, for when a test does grade in the background.
- **Tests (+10, backend 1838 -> 1848; ai-service and frontend unchanged and passing).**
  - `SubmissionGraderTests` (7, mocked repositories and a scripted `SandboxRunner` behind the real `SubmissionTestRunner`): inputs in run order with the configured file name, code and limits; one row per test with outcome, output, stderr and runtime, public and hidden alike; NUL replaced after a passing comparison; sandbox failure throws and stores nothing; no tests, a missing submission, and an unconfigured language each run and store nothing.
  - `RealHttpGradingTests` (3, real Docker and Postgres through the HTTP endpoint and the application's own worker): passed/passed/failed across public and hidden tests with real outputs; a crashing program is `GRADED` with `RUNTIME_ERROR` rows and the traceback in `stderr`; a printed NUL is stored as U+FFFD and the submission is still `GRADED`.
  - Mutation-checked, each failing at least one test: NUL kept, results not stored, limits swapped, hidden tests skipped, the no-tests guard removed, the missing-submission guard removed, stdout and stderr swapped.

## 5.3b — A compile error short-circuits: every test recorded as `COMPILE_ERROR`, score zero for tests criteria

- **The short-circuit is enforced in `SubmissionGrader`, not only trusted to the runner.** `DockerCliSandboxRunner.runAll` already builds once and gives every input the same `COMPILE_ERROR` without starting a test container (4.4b, 4.6a). But `SandboxRunner`'s default `runAll` builds per input, and could in principle disagree with itself. So the grader checks: if any result is `COMPILE_ERROR`, every row is stored as `COMPILE_ERROR` from that one build, and no test can pass on code that did not build.
- **Row shape: `actual_output` NULL, `runtime_ms` NULL, the compiler's output in `stderr`.** V8's column comment already says a compile error has no program output (NULL), and no program ran, so there is no runtime: the build's time is not the program's. The compiler's output is what the student needs to read ("compile errors are reported as such", spec). It is stored in `stderr` as the compiler's stdout followed by its stderr, each only if non-empty, with a newline between them if the first does not end with one (`compilerOutput`). javac reports on stderr, but a configured compiler might report on stdout, and adding a language must never need code. The text is repeated on every row, because the schema has no per-submission place for it; adding a column was not part of this row. NULs are replaced as in 5.3a.
- **Still `GRADED`:** the grader returns normally, so the worker writes `GRADED` (5.2b). Only a `SANDBOX_ERROR` during the build (the sandbox failed, not the code) is `ERROR`; it stays a `SandboxFailedException`, as before.
- **"Score zero for tests criteria"** needs no code here: scoring is 5.4a, and its passed fraction counts only `PASSED` rows, which a compile error never has. This is noted in `backend/CLAUDE.md` for 5.4a, and 5.6a's hand-computed compile-error case will check it.
- **Tests (+6, backend 1848 -> 1854; ai-service and frontend unchanged and passing).**
  - `SubmissionGraderTests` (+5):
    - every test `COMPILE_ERROR` with the compiler's output and no program output or runtime, public and hidden, and the grader returns;
    - one `COMPILE_ERROR` among passing results turns every row into `COMPILE_ERROR`;
    - the joining of the compiler's two streams;
    - a compiler that reports on stdout;
    - a NUL in the compiler's output.
  - `RealHttpGradingTests` (+1): real Java that does not compile, submitted over HTTP, is `GRADED` with two `COMPILE_ERROR` rows holding javac's message, the same text on both, and NULL output and runtime.
  - Mutation-checked: each of these makes at least one test fail:
    - no short-circuit;
    - program output kept;
    - runtime kept;
    - only stderr kept;
    - streams joined without a newline;
    - output not made storable.

## 5.4a — Rubric scoring service: pure function from (rubric, test results) to criterion scores and total

- **A pure static function, `service/RubricScoring.score(criteria, tests, entered)`.** It has no Spring bean, no repository and no clock; the inputs are small records, so 5.6a can hand-compute it and 5.4b/5.4e call it with what they read. A `TESTS` criterion scores `weight × (sum of passed test weights ÷ sum of all its test weights)`, the spec's formula. Only `PASSED` counts, which gives 5.3b's "score zero" for a compile error. A test without a result counts as not passed (the caller decides by setting `passed`).
- **The function takes entered scores for non-`TESTS` criteria now, although `MANUAL` only becomes available in 5.4d.** The row says non-`TESTS` criteria are reported as pending until their phase lands, and 5.4e must "recompute through the pure scoring function (5.4a)" when a manual score is saved. So the function needs that input from the start rather than a second signature later. With nothing entered, such a criterion is `PENDING`: its score is null, it adds nothing to the total, and it still counts in the maximum. `Result.hasPending()` is what 5.4e will call provisional.
- **Rounding: each criterion score is rounded once, to 2 decimals (`SCALE`), `HALF_UP` (`ROUNDING`); the total is the exact sum of the rounded criterion scores.**
  - The scale is V9's `NUMERIC(7, 2)`, whose comment already says the scoring service rounds to it.
  - The criterion is computed as `weight × passed ÷ total` in one `BigDecimal` division, so no intermediate fraction is rounded.
  - Summing the rounded criterion scores means the stored total always equals the sum of the stored criterion scores. That is 5.4e's "displayed total can't drift from the criteria"; the price is that it can differ by a cent from the unrounded sum, e.g. 16.67 + 16.67 = 33.34 rather than 33.33.
  - `HALF_UP` is the usual convention for grades. No `*.md` names a rounding mode, so it is a named constant the human can change; the tests pin the difference from `HALF_EVEN` (0.125 → 0.13, 0.625 → 0.63).
- **No division by zero:** a `TESTS` criterion with no tests, or tests whose weights add up to 0, scores 0.00. It is graded, not pending, as the row asks; test weights are at least 1 through the API, but the function does not rely on that.
- **Contradictory inputs throw `IllegalArgumentException` instead of being guessed at:**
  - a test of an unknown or non-`TESTS` criterion;
  - a test or criterion listed twice;
  - an entered score for a `TESTS` or unknown criterion;
  - an entered score outside 0 to the criterion's weight.

  In grading this ends the submission in `ERROR` (5.2b) rather than storing a wrong grade. 5.4d validates the range at the endpoint first, as its row says.
- **The nested record is `ScoredTest`, not `TestOutcome`,** so it is not confused with the sandbox's `TestOutcome` enum.
- **Tests (+15, backend 1854 -> 1869; ai-service and frontend unchanged and passing):** `RubricScoringTests`, every expected value computed by hand in a comment:
  - a partial pass;
  - test weights deciding the fraction;
  - two criteria of different weights;
  - the total as the sum of rounded scores (33.34);
  - two half-up roundings;
  - everything passed;
  - a compile error (0, graded);
  - no tests and zero test weight;
  - a pending manual criterion;
  - an entered score;
  - entered scores at 0 and at the weight;
  - scale and order;
  - an empty rubric;
  - each refused contradiction.

  Mutation-checked; each mutant failed at least one test:
  - `HALF_EVEN`;
  - the total altered;
  - test weights ignored;
  - the criterion weight ignored;
  - the zero guard removed;
  - pending scored as 0;
  - max excluding pending criteria;
  - the entered range unchecked;
  - tests of non-`TESTS` criteria accepted;
  - a duplicate test accepted.
- **CI failures on PR #108: a lost hand-over in 5.2a's dispatcher, not 5.4a code.** `RealHttpSubmissionWorkerTests.afterARestartEverythingWaitingIsGradedOldestFirst` (5.2c) timed out on CI in 3 of 4 runs and never locally.
  - **Not the cause:** the first fix (counting the holding processor's submission as running before recording it as taken) closed a real but unrelated race in the test harness, proven by widening its window. It did not stop the CI failure.
  - **The evidence:** timeout diagnostics added to the test showed every worker idle with an empty queue, after the first submission was graded and three rows still `QUEUED`. The pool was never told about them.
  - **The cause:** in `SubmissionDispatcher.offer`, a submission marked handed over but refused by a full queue was briefly still marked. In that window, a worker that had just taken a task, and was offering the same submission from its pickup, skipped it as already handed over. The refused offer then removed the mark, so nobody held the submission, and it would wait `QUEUED` until the next submission or a restart.
  - **When it happens:** the trigger is startup recovery's back-to-back offers with an idle worker. The worker's pickup query finished inside the time the refusal took to build its exceptions, which on CI's slower runner was common.
  - **The fix:** `offer` is now `synchronized`, so "handed over" only ever means "in the pool".
  - **Its test:** `SubmissionDispatcherTests.aRefusedOfferNeverHidesASubmissionFromAPickupAtTheSameMoment` runs 3000 rounds of exactly that sequence. It failed on round 1 in 3 of 3 runs before the lock and passes after it. The timeout report stays in the test.
- **The residual memory-kill flake (4.7a), fixed on this PR at the human's request.**
  - **The failure:** after the dispatcher fix, a CI run failed on `SandboxExitStatusTests.aHelperKilledForMemoryAndAMainProgramThatThenFailsIsAMemoryLimit`. The parent exits 1 after its helper was killed for memory, and Docker did not record `OOMKilled`, so the run was `RUNTIME_ERROR`. 4.7a had left that path open on purpose.
  - **The decision:** asked for a recommendation, I proposed (a), letting the test accept either label, over (b), reading the container's cgroup `memory.events`. (b) is not in `docker inspect`, the container is removed straight after the run, it depends on cgroup v1/v2, and it cannot be done from a Windows host with Docker Desktop: fragile code for a label that does not change the score. The human agreed and asked for it on this PR.
  - **The change:** the test is renamed `…IsAMemoryLimitOrARuntimeError`. It accepts `MEMORY_LIMIT` or `RUNTIME_ERROR` and still asserts exit code 1 and that the run never completes cleanly, so it can never pass a test. "Non-zero exit plus the flag is `MEMORY_LIMIT`" stays pinned without Docker in `ExitClassificationTests` (`1, true, MEMORY_LIMIT`).

## 5.4b — Persist `grades` and `criterion_scores`

- **One transaction for the whole result (`service/SubmissionResultWriter.store`).** `SubmissionGrader` no longer saves `test_results` itself. It hands the rows to the writer, which in one `@Transactional` method reads the rubric, replaces the results, scores them with `RubricScoring` (5.4a), and stores the `TESTS` criteria's `criterion_scores` and the `grades` row. So a submission never has results without a grade, or a grade that disagrees with its results. If scoring refuses its inputs, the transaction rolls back and the earlier state is untouched (tested). The sandbox still runs before, outside any transaction.
- **Storing replaces, as 5.2c required.** A submission graded again (after a restart cut its first run short) first loses its earlier `test_results`, its `TESTS` criterion rows and its grade. The `grades.submission_id` and `criterion_scores (submission_id, criterion_id)` unique constraints would otherwise refuse the second run. Recovery already deletes `test_results` of `RUNNING` rows; the writer deleting them too makes it idempotent on its own.
- **Entered scores are kept and counted.** Rows of non-`TESTS` criteria (only `MANUAL` from 5.4d) are never deleted by grading. They are read in the same transaction and passed to `RubricScoring` as entered scores, so the stored total includes a manual score given before a re-run. 5.4e will reuse the same function when a manual score is saved.
- **A pending criterion has no row,** as V9's comment says ("no row is pending, not zero"). The grade's `max_score` still counts its weight (5.4a).
- **`graded_at` is written from the application `Clock`** (microseconds), like `submitted_at` / `started_at` / `finished_at`. The column's `DEFAULT NOW()` is only for SQL inserts.
- **Scores are `BigDecimal` mapped to `NUMERIC(7, 2)`** (`precision = 7, scale = 2` on the new `Grade` and `CriterionScore` entities), as the row requires; never `double`.
- **No tests still means nothing stored, so no grade row** (5.3a's decision). The 5.1 test fixtures publish assignments without tests by SQL. A grade written in the background for each of their hundreds of submissions would race with `emptyTables` deleting `submissions`, as a foreign-key failure. A published assignment always has tests (3.4b), so every real `GRADED` submission has a grade.
- **A test case without a criterion fails loudly:** `Objects.requireNonNull`, ending in `ERROR`. The column is nullable, but the service always sets it (3.5a).
- **Tests (+6, backend 1870 -> 1876; ai-service and frontend unchanged and passing).**
  - `SubmissionResultWriterTests` (5, real database, values computed by hand in comments):
    - results, `TESTS` scores and grade stored together, with a pending criterion and no row for it;
    - grading again replaces results, scores and grade, and keeps one grade row;
    - an entered score is kept with its comment and counted in the total;
    - a test without a result is not passed;
    - a refused scoring changes nothing, not even the earlier grade.
  - `SubmissionGraderTests` (+1): the tests as run are handed on for scoring; the other tests now verify the writer call.
  - `RealHttpGradingTests`: the end-to-end runs now check the stored grade (66.67, and 0.00 for a compile error).
  - Mutation-checked, each failing at least one test:
    - the earlier grade kept;
    - entered scores deleted;
    - entered scores ignored;
    - every graded criterion stored;
    - `graded_at` from the system clock;
    - not transactional;
    - earlier results kept;
    - anything but `FAILED` counted as passed.

## 5.4c — `GET /assignments/{id}/my-grade`: best score across the student's graded attempts

- **Answer shape: 200 `{assignmentId, best}`, with `best` = `{submissionId, attemptNo, totalScore, maxScore, gradedAt}` or `null`.** No `*.md` names one. "No graded attempt yet" is an ordinary state for the course page (9.2a), not an error, so it is a 200 with `best: null` (the key is sent) rather than a 404 the frontend would have to tell apart from `ASSIGNMENT_NOT_FOUND`. `submissionId` and `attemptNo` are the row's "returns which submission produced it". `maxScore` goes with the total so the client can show "85.50 / 100.00". Scores go out as stored, two decimals (`85.50`).
- **Ties: of equal totals, the earliest attempt counts.** CLAUDE.md says only "the best score counts". The earliest attempt is the one that first reached the score, and the choice is stable: a later attempt with the same total never changes which submission is named.
- **A graded attempt is a `GRADED` submission with a `grades` row; any other grade row is ignored.** A crash after the grade commit but before `GRADED` leaves a grade on a submission that recovery puts back to `QUEUED` (5.2c only deletes `test_results`). Until it is graded again it can be `QUEUED` or `RUNNING`, and a re-run that then fails ends `ERROR` with the old grade still there (the writer only replaces on success). Those totals are not results the student has, so they never count. A submission graded without tests has no grade row (5.3a) and is not an attempt with a score.
- **Totals are compared directly, not as a percentage.** The rubric is locked after the first submission (3.4a), so every attempt's `max_score` is the same.
- **Who may ask: students only, with `getVisibleTo`'s rule.** "My" grade has no meaning for a teacher or admin (they get grades through 5.5b/10.x), so they are 403 by role. A student who is not enrolled, or whose assignment was unpublished after they submitted, gets the 404 of a missing id, as for every other read of the assignment (3.3b). The student's grade is not lost: re-publishing shows it again.
- **One query, best first, `Limit.of(1)`:** `GradeRepository.findGradedAttemptsBestFirst` joins `grades` to `submissions` in JPQL (a constructor expression into the `GradedAttempt` record) and orders by `total_score desc, attempt_no asc`. It uses the `(assignment_id, student_id, …)` unique index (1.2g). Phase 10's statistics and progress table can take the same rule from it.
- **Tests (+12, backend 1876 -> 1888; ai-service and frontend unchanged and passing).**
  - `RealHttpMyGradeTests` (7, real HTTP, submissions and grades inserted by SQL with their final status so no worker grades them):
    - the highest total counts and the answer names its submission (exact key sets, attempt number, scale);
    - of equal totals the earliest attempt;
    - no graded attempt (queued, running, error, graded without tests) is `best: null`;
    - a grade row on a non-`GRADED` submission never counts;
    - only the student's own attempts at this assignment count;
    - not enrolled and a draft are byte-identical to the 404 of a missing id;
    - teacher and admin 403, anonymous 401.
  - `RoleMatrixIntegrationTests`: a row for the endpoint (+5).
  - `RealHttpGradingTests`: after a real grading run, `my-grade` names that submission with 66.67.
  - `RealHttpHiddenTestSweepTests` now sweeps the endpoint too (found by itself, no change).
  - Mutation-checked, each failing at least one test:
    - no `GRADED` filter;
    - lowest total first;
    - the latest of equal totals;
    - another student's attempts counted;
    - another assignment's attempts counted;
    - no visibility check.

## 5.4d — Enable `MANUAL`: `PUT /submissions/{id}/manual-scores/{criterionId}`

- **`MANUAL` is available (`CriterionType.MANUAL(true)`), with one guard it needs: a `TESTS` criterion that has test cases cannot change type** (409 `CRITERION_TYPE_HAS_TESTS`). Before 5.4d no other type could be chosen, so this never came up. Changing such a criterion to `MANUAL` would leave test cases under a non-`TESTS` criterion. `RubricScoring` refuses that input (5.4a), so every submission to the assignment would end in `ERROR`. The check runs under the assignment's row lock, which adding a test also takes, so it is exact. Without tests the type may change either way. A new error code rather than `CRITERION_HAS_TESTS`, whose fixed message is about deleting.
- **Who: `hasAnyRole('TEACHER', 'ADMIN')` plus `requireManageable` on the submission's assignment** ("course teacher or admin"). A caller who may not manage it gets the new 404 `SUBMISSION_NOT_FOUND`, byte-identical to a missing id, before anything about the body is checked: the 404 convention of 3.1c, applied to submissions. Students are 403 by role, even on their own submission.
- **The order of the other checks:** criterion of this assignment (404 `CRITERION_NOT_FOUND`, the rubric's code), then `MANUAL` (400 `CRITERION_NOT_MANUAL`), then the submission `GRADED` (409 `SUBMISSION_NOT_GRADED`), then the range (400 `MANUAL_SCORE_OUT_OF_RANGE`). Path problems come before state, and state before the value.
- **Only `GRADED` submissions can be scored.** A `QUEUED`/`RUNNING` one has no result yet for the teacher to judge, and an `ERROR` one gave its attempt back (5.1d): it is not an attempt with a score. A score would be safe from re-grading either way, because grading never deletes non-`TESTS` rows (5.4b).
- **The score: 0 to the weight inclusive, at most two decimals, never rounded.** `NUMERIC(7, 2)` is the stored scale. A third decimal is refused as `VALIDATION_FAILED` (`@Digits(integer = 3, fraction = 2)`; weights are at most 100) rather than rounded, so the teacher's value is stored exactly. The range depends on the criterion, so it is a check in the service with its own code, not a bean-validation field error.
- **The comment:** optional, blank = none, otherwise stored as sent. Cap `COMMENT_MAX` 5000 is a placeholder, the same as a course description (flagged in the PR).
- **PUT replaces the whole entry**: saving again overwrites the score and the comment, and a body without a comment clears it. That is PUT's usual meaning, and the teacher always sends the form's current state.
- **One native upsert (`INSERT ... ON CONFLICT (submission_id, criterion_id) DO UPDATE`):** the unique constraint decides, so two teachers saving at the same moment never cause a 500. The later write wins, as with any PUT. `max_score` is the criterion's weight, as for computed rows. `comment` is cast to `text` in the statement, so a null binds with a type.
- **The grade is not touched here.** The `grades` total is recomputed through `RubricScoring` in 5.4e, its own row ("never patch totals by hand"). Until then a saved manual score shows in `criterion_scores` only, and is counted the next time the submission is graded (5.4b).
- **Found while enabling `MANUAL`, left for the human (`docs/suggestions.md`):** an all-`MANUAL` rubric publishes, and its submissions are `GRADED` with no grade row (no tests = nothing stored, 5.3a).
- **Tests (+22, backend 1888 -> 1910; ai-service and frontend unchanged and passing).**
  - `RealHttpManualScoreTests` (13, real HTTP, submissions inserted by SQL with their final status):
    - the owner's score and comment stored (key set, two decimals, `max_score` = weight);
    - saving again replaces both;
    - a blank comment stored as none;
    - an admin;
    - a weaker attempt;
    - 0 and the weight accepted;
    - -0.01, weight + 0.01 and 100 refused with nothing changed;
    - three decimals, missing or null score, a comment over the cap are `VALIDATION_FAILED` (the cap itself accepted);
    - a `TESTS` criterion;
    - another assignment's criterion byte-identical to a missing one;
    - `QUEUED`/`RUNNING`/`ERROR` refused;
    - another teacher byte-identical to a missing submission, whatever the body;
    - the student 403 and anonymous 401.
  - `RealHttpRubricTests` (+2 net):
    - `MANUAL` added;
    - `TESTS` without tests to `MANUAL` and back;
    - `TESTS` with tests refused with nothing changed (a rename that keeps the type still allowed);
    - the "not available" cases now `STATIC_ANALYSIS`/`OPEN_ANSWER` only.
  - `RealHttpPublishRulesTests` (+1): `TESTS` 80 + `MANUAL` 20 without tests publishes.
  - `RealHttpTestCaseTests` (+1): a `MANUAL` criterion takes no tests.
  - `RoleMatrixIntegrationTests` (+5): a row for the endpoint.
  - Mutation-checked, each failing at least one test:
    - `MANUAL` still unavailable;
    - no type guard;
    - a guard that also refuses keeping the type;
    - any criterion type scored;
    - any status scored;
    - no lower bound;
    - no upper bound;
    - no access check;
    - the assignment's 404 shown;
    - `max_score` from the score;
    - an upsert that keeps the old row;
    - an upsert that keeps the old comment.

## 5.4e — Recompute the total when a manual score is saved; the provisional flag

- **One code path writes a score: `SubmissionResultWriter.scoreAndStore`, used by grading (`store`) and by the new `rescore`.** `rescore` reads what is stored: the submission's `test_results`, the assignment's tests, and every entered score. It feeds them to `RubricScoring` and replaces the `TESTS` rows and the grade, exactly as grading does. "Never patch totals by hand": the save does not add the new points to the old total, and nothing outside the writer computes one. Re-writing the `TESTS` rows from the same inputs gives the same values, and keeps the rule "the total is the sum of the stored criterion rows" true by construction.
- **In the save's transaction, under the submission's row lock.** Two teachers saving different criteria of one submission at the same moment would otherwise each total the scores without the other's, under READ COMMITTED. The last total to commit would then miss a score. The save locks the submission (`findByIdForScoreChange`) after the access check, so a caller who may not manage it never takes the lock, and before anything is read. The second save waits, and its recompute counts the first's score. Tested with a held lock and an uncommitted score, as in 3.6b, and mutation-checked.
  - **`FOR NO KEY UPDATE`, not `FOR UPDATE`:** it only has to exclude another save. `FOR UPDATE` would also make every insert that references the submission (`criterion_scores` of another request, `ai_feedback` hints in Phase 7) wait, because their foreign keys take a key-share lock.
  - **Native SQL**, so the lock taken is the one written.
- **A submission without a grade gets one at its first save** (the human's choice after 5.4d, option 1, `docs/suggestions.md`). With an all-`MANUAL` rubric, grading stores nothing (no tests, 5.3a). `rescore` creates the grade, provisional until the last criterion is scored. Grading is unchanged and still stores nothing without tests.
- **The provisional flag is stored: `grades.provisional` (V11), written with the total from the same `RubricScoring` result (`hasPending()`).** 1.2i left the representation to 5.4e. Deriving the flag at every read (a not-exists subquery over the rubric and the criterion rows) was the alternative. Storing it was chosen because:
  - it is written in the same statement as the total, from the same computation, so the two cannot disagree;
  - every later reader can take it from the grade row as it is (5.5a/b views, Phase 10 statistics, CSV export), instead of repeating a subquery;
  - the rubric is locked after the first submission, and entered scores are never deleted, so nothing else can change it between writes.

  The flag is generic: any non-`TESTS` criterion still pending (only `MANUAL` today; `OPEN_ANSWER`'s AI-proposed scores in Phase 15 will need their own rule).
- **The migration, V11:** `provisional BOOLEAN NOT NULL DEFAULT FALSE`, then backfills existing grades with the same rule. The default only serves SQL inserts: the application always writes the value.
  - **Dev seed, V1003:** on a new dev database V11 runs before V1002, whose grade inserts then get `false`, so V1003 sets the seeded flags with the same rule. On an existing dev database V11 is applied out of order (2.2a) and V1003 changes nothing.
  - The four seeded grades with a pending `MANUAL` criterion are provisional (tested).
  - V11's own backfill is not tested on its own. Migrations run before any test can insert data, and V1003 runs the same `WHERE` condition.
- **`graded_at` is the time of the last recompute**, from the `Clock`: the grade is "when it was computed" (5.4b).
- **The answers:**
  - the save now includes the recomputed `grade: {totalScore, maxScore, provisional}`, which is what 9.6c's form shows after a save;
  - `my-grade`'s `best` has `provisional`. A provisional total counts as it stands: it is the student's score until the teacher adds the rest.
- **Tests (+13, backend 1910 -> 1923; ai-service and frontend unchanged and passing).**
  - `RealHttpManualRecomputeTests` (5, real HTTP, graded through `SubmissionResultWriter.store`, totals hand-computed):
    - each save recomputes (15.00 -> 37.50 provisional -> 44.50 -> 52.00, B replaced, not added) and the last pending criterion clears the flag, with `graded_at`, one grade row, total = sum of rows, and `my-grade`;
    - the best attempt switches when a recompute lifts attempt 2 (55.00) above attempt 1 (45.00);
    - an all-`MANUAL` rubric gets its grade at the first save (63.50 provisional, then 93.50);
    - a refused save leaves the grade untouched;
    - a save waits for a held lock and counts the other save's score (55.00).
  - `SubmissionResultWriterTests` (+3):
    - `rescore` from stored results with a new entered score (46.00);
    - creating the grade without tests (45.50 provisional);
    - refusing to run outside a transaction.
  - The flag is also asserted on the existing store cases.
  - `RealHttpMyGradeTests` (+1): a provisional best.
  - `GradesMigrationTests` (+2): the column (boolean, `false` by default, required).
  - `DevSeedSubmissionsTests` (+2): V1003 applied, and exactly the four pending grades provisional.
  - `RealHttpManualScoreTests`: the answer's key set includes `grade`, and row counts look at the manual criterion only (the recompute now stores the `TESTS` row too).
  - `FlywayProdProfileTests` / `FlywayDevProfileTests`: the applied-migration counts go from 10 to 11 and from 13 to 15. The first full run failed on exactly these, and `backend/CLAUDE.md` now says a new migration bumps them.
  - Mutation-checked, each failing at least one test:
    - no recompute;
    - never provisional;
    - no submission lock;
    - `rescore` ignoring the stored results;
    - `rescore` ignoring the tests;
    - `my-grade` never provisional;
    - the seed's flags inverted;
    - `rescore` in a transaction of its own.

## 5.5a — `GET /submissions/{id}`: student view (own submissions only)

- **Another student's existing submission is a 403, a missing id a 404: the human's decision, asked before writing (it was a gate).** Task 5.6c says "other student gets 403". Every endpoint before this answers the 404 of a missing id to anyone who may not see the thing (3.1c), because a 403 tells which ids exist. I recommended the 404; the human chose the 403. It is recorded in `backend/CLAUDE.md` as the one exception. The 403 body is the standard `ACCESS_DENIED` and shows nothing of the submission.
- **Students see the rubric breakdown: the human's decision, asked at the same time.** 3.4a kept the rubric teacher-only and left open what a student may see, while the spec's result page shows a "rubric breakdown".
  - **What is shown:** per criterion, in rubric order: name, type, score (null while pending), max (the weight), and a teacher's comment.
  - **No criterion ids, and the tests carry none either,** so which hidden test belongs to which criterion stays hidden.
  - `GET /assignments/{id}/rubric` stays teacher-only: the breakdown comes with a submission, not before one.
- **Ownership alone decides who may read it (my call).** "Own submissions only" is the row's rule. A student who has left the course (3.2b: "past work stays") or whose assignment was unpublished still reads their own submission. `my-grade` (5.4c) follows the assignment's visibility instead, because it is a read of the assignment. Teachers and admins are 403 by role until 5.5b adds their projection to the same URL.
- **Hidden tests: pass or fail only, built on 3.5b's masked types.** The row says no hidden input, expected output or actual output.
  - **Also left out: stderr.** A student's program can print its stdin to stderr, so a hidden test's stderr would hand the student the hidden input.
  - **Also left out: the outcome** (`TIMEOUT`, `RUNTIME_ERROR`, …) **and the runtime.** "Pass/fail" is the rule CLAUDE.md sets for the AI, and both say something about the hidden input.
  - **How:** the test list comes from `StudentTestCasesResponse.of` (the only place a `TestCase` becomes student-visible), and a hidden one becomes a `HiddenResult` record that has no component for any of these. A test without a result counts as not passed.
- **Compiler output is shown once, at the top (`compilerOutput`).** It is built from the student's own code before any test input exists, and the same text is on every row (5.3b). Shown at the top, a student whose tests are all hidden still sees why the build failed.
- **Nothing of the result before `GRADED`.** The worker stores results and grade, then marks the submission `GRADED` (5.2b). In between, a `RUNNING` submission already has everything stored. The view waits for the status, so a student never sees a result whose status says it is not finished. `ERROR` shows none either.
- **One service, two shapes:** `SubmissionViewService` loads `SubmissionDetails` (everything stored, hidden content included). 5.5b adds the teacher's projection of the same record and the same URL, as its row asks.
- **Tests (+19, backend 1923 -> 1942; ai-service and frontend unchanged and passing).**
  - `RealHttpStudentSubmissionViewTests` (10, real HTTP, graded through `SubmissionResultWriter.store`). Raw JSON key sets and a search of the raw body for every hidden string (name, input, expected, actual output, stderr):
    - the full view with a hand-computed grade and breakdown (35.00 provisional; A 35.00/70.00, M pending);
    - a passed hidden test;
    - a teacher's manual score and comment (59.00);
    - a compile error;
    - `RUNNING` and `ERROR`;
    - results stored while still `RUNNING`;
    - graded without a grade;
    - after leaving the course and an unpublish;
    - another student 403 and a missing id 404;
    - teacher/admin 403, anonymous 401.
  - `StudentTestResultResponseTests` (3): the component names of `HiddenResult`, `PublicResult`, the view and `CriterionView`.
  - `RealHttpHiddenTestSweepTests` (+1): its world has the student's graded submission with two more markers in the hidden result (actual output, stderr), and it resolves `{submissionId}`.
  - `RoleMatrixIntegrationTests` (+5): a row for the endpoint.
  - Mutation-checked, each failing at least one test:
    - any student reading any submission;
    - hidden tests unmasked (the sweep fails too);
    - the hidden result's output and stderr leaked through its name (the sweep fails too);
    - a wrong pass flag;
    - the comment dropped;
    - no compiler output;
    - results before `GRADED`;
    - the grade before `GRADED`.
  - **Found by mutation:** the "results before `GRADED`" mutant first survived, until the `RUNNING`-with-stored-results test was added.

## 5.5b — Teacher view of `GET /submissions/{id}` (course owner or admin)

- **One authorization path, a shape per role, as 3.5b's test list.**
  - `SubmissionViewService.view(userId, role, id)` decides who may read; the controller picks `StudentSubmissionResponse` or `TeacherSubmissionResponse` by the token's role, behind the sealed `SubmissionResponse`.
  - The role rule moved from `hasRole('STUDENT')` to "any signed-in user" (no annotation, 2.5a). The role-matrix row now says everyone reaches the 404 of a missing id.
- **Another teacher gets the 404 of a missing id, not a 403.** The human's 403 at 5.5a was for "other student" (5.6c's wording) and is scoped to students. For teachers, the 3.1c convention has a default. The manual score of the same submission (5.4d) already answers another teacher with this 404. A 403 here would tell them which submission ids exist in other teachers' courses. An admin reads every submission.
- **The teacher's shape is everything stored**:
  - the student's id (not their email: the roster has it, 3.2b, and the view stays one row);
  - each criterion with its `id`, the ids the manual-score endpoint takes, and its `weight`;
  - every test in full with its `criterionId`, hidden ones included, and its whole result.

  A separate record from the student's, so the student's masking stays in the type (3.5b).
- **The same `GRADED` gating as the student's view**: a stored grade or result of a submission that is not `GRADED` is not its result (a stale grade can survive on an `ERROR` re-run, 5.4c). The teacher sees the status, the code and the times meanwhile.
- **Shared, harmless parts moved to `SubmissionResponse`:** `GradeView` and `compilerOutput`. The student's JSON is unchanged (5.5a's tests pass as they were).
- **Tests (+6, backend 1942 -> 1948; ai-service and frontend unchanged and passing).**
  - `RealHttpTeacherSubmissionViewTests` (6):
    - the owner sees everything: raw key sets, the hidden test's name, input, expected and actual output and stderr, the criterion ids and weights, and a hand-computed 35.00 provisional;
    - an admin's body is byte-identical to the owner's;
    - the view's criterion id works with the manual-score endpoint (59.00 after);
    - nothing before `GRADED`;
    - another teacher byte-identical to a missing id;
    - the student gets their own shape on the same URL.
  - `RealHttpHiddenTestSweepTests`: the positive control now also requires the owner's and the admin's submission view to show all five markers, including the hidden result's actual output and stderr (5.5a promised it).
  - `RealHttpStudentSubmissionViewTests`: the test that expected teachers to be refused became "anonymous 401" only.
  - `RoleMatrixIntegrationTests`: the row is now "any signed-in user".
  - Mutation-checked, each failing at least one test:
    - teachers reading any submission;
    - another teacher told the submission exists (403);
    - students judged as teachers;
    - students given the teacher shape;
    - teachers given the student shape;
    - results before `GRADED` for the teacher;
    - a wrong criterion id.

## 5.5c — `GET /assignments/{id}/submissions` (teacher) and `GET /assignments/{id}/my-submissions` (student)

- **Who:**
  - **The teacher's list** follows the rubric's read rule: `hasAnyRole('TEACHER', 'ADMIN')` plus `requireManageable`. Another teacher gets the 404 of a missing assignment. A draft's submissions are listed: unpublishing never hides work from its teacher.
  - **The student's list** follows `my-grade`: `hasRole('STUDENT')` plus `getVisibleTo`. Not enrolled, or a draft, is the 404 of a missing id. A single own submission stays readable through `GET /submissions/{id}` (5.5a), which only asks for ownership. The list is a read of the assignment; the view is a read of the student's own work.
- **What a row carries:**
  - id, attempt, status, `submittedAt`, `finishedAt`, and the grade summary `{totalScore, maxScore, provisional}`;
  - **for the teacher, also the student** as the roster's `{id, email, fullName}` (3.2b), so the list can be read without a lookup per row;
  - **no code and no results:** those are one `GET /submissions/{id}` away.
- **The grade shows only on a `GRADED` row with a grade**, for the same reason as the submission view: a grade on a re-queued or failed re-run is not a result (5.4c). A submission graded without tests shows none (until a teacher's first score, 5.4e).
- **Order: id, oldest first**, the fixed sort of every list so far (2.5c). It also reads as attempts 1, 2, 3 in a student's history. No filters (by student, by status): the row does not ask for them, and they can be added without changing the shape.
- **One projection query per list** (`SubmissionRow`: submission join user, left join grade). The `countQuery` is explicit, because Spring Data cannot derive a count from a constructor expression over joins. `Sort.by("id")` comes from the controller, as in the other lists.
- **Tests (+19, backend 1948 -> 1967; ai-service and frontend unchanged and passing).**
  - `RealHttpSubmissionListTests` (9, real HTTP):
    - the teacher's list: order, row and student key sets, grade summary, another assignment's left out, no code;
    - grades shown only for `GRADED` rows (stale grades on `QUEUED`/`ERROR`, graded without tests);
    - pagination and an admin;
    - a draft's submissions for the owner;
    - another teacher byte-identical to a missing assignment, student 403, anonymous 401;
    - the student's own list: order, key set, grade, a classmate's and another assignment's left out;
    - the student's pagination and a bad page;
    - not enrolled and a draft byte-identical to a missing assignment;
    - teacher and admin 403 on the student's list.
  - `RoleMatrixIntegrationTests` (+10): a row for each endpoint.
  - The hidden-test sweep covers both endpoints by itself (`{assignmentId}`).
  - Mutation-checked, each failing at least one test:
    - the teacher's list without its access check;
    - the student's list without its visibility check;
    - the student's list showing classmates;
    - the teacher's list showing another assignment's submissions;
    - a grade shown before `GRADED`;
    - newest first;
    - an inner join that drops rows without a grade.

## 5.6a — Scoring unit tests with hand-computed expected values

- **Most of the row was already done by 5.4a, so this commit adds only what was missing.** `RubricScoringTests` (15 tests, each value worked out by hand in a comment) already had a partial pass, mixed criterion and test weights, a compile error, and no tests / zero weight. Repeating those would only be noise. What it lacked were combinations, and a rounding-down case.
- **Tests (+3, backend 1967 -> 1970; ai-service and frontend unchanged and passing), values by hand in the comments:**
  - nothing passed across two criteria with different test weights: 0.00 each, total 0.00 of 100.00, graded not pending;
  - a compile error with a teacher's manual score of 22.5 entered: the total is 22.50, the tests criterion adds nothing;
  - a full rubric: 50 × 7/10 = 35.00, 30 × 4/7 = 17.142857... = 17.14, a manual 15.50, total 67.64 of 100.00, and the same result with the test list reversed.
- **Mutation-checked:** rounding `UP` instead of `HALF_UP` was not caught by any 5.4a test (they only had values that round up or to a half); it now fails the full-rubric test through 17.14. The `HALF_EVEN` and total mutants stay caught by the 5.4a tests.
- **The row's wording matched the repo closely enough not to be a gate:** it asks for tests, and tests exist; the gap was coverage, not scope.

## 5.6b — Integration test: submit → worker runs → `GRADED` → results and grade

- **A new class on the dev seed, not more cases in `RealHttpGradingTests`.** The row says to use the seeded Java and Python assignments (1.4b). `RealHttpGradingTests` builds its own small Python world; the seed has the real rubrics (a weight-2 test, two `TESTS` criteria, a `MANUAL` one), so the weighted arithmetic is exercised on the data a developer actually runs against.
- **`RealHttpSeededGradingTests` does not extend `RealHttpTestBase`,** because its `emptyTables` would delete the seed before every test. It is a `@SpringBootTest` with `spring.flyway.locations` including `db/dev-seed` (as the `DevSeed*Tests`), so it has its own context and database: the cost is one more cached context.
- **Everything goes through HTTP as the seeded people:** `POST /auth/signin` with the passwords documented in V1000, `POST .../submissions`, polling the student's own `GET /submissions/{id}` until `GRADED` (QUEUED/RUNNING allowed, anything else fails; 3 minutes), then the same id as the teacher. Local dev host and seed values only.
- **No test assumes an attempt number.** The 7 seeded submissions stay, so Java (unlimited) tests can run in any order; each Python student has exactly one attempt left (max 3, two seeded), used by one test each.
- **Tests (+5, backend 1970 -> 1975; ai-service and frontend unchanged and passing), totals by hand from V1001:**
  - correct Java: five tests of weights 1, 1, 1, 1, 2 all pass, 80 × 6/6 = 80.00, Code quality pending, total 80.00 of 100.00, provisional; one `criterion_scores` row only; the student sees two public tests in full and three hidden as `{id, name: "Hidden test N", visibility, position, passed}` with `4000000000` nowhere in the body; the teacher sees the hidden test's input, expected and actual output;
  - `int` overflow: weight-2 test fails, 80 × 4/6 = 53.333... = 53.33; the teacher sees `-294967296`;
  - missing semicolon: 0.00, graded, `compilerOutput` mentions `Main.java`, every test `COMPILE_ERROR`;
  - Python ignoring case: Basic 40 × 3/3 = 40.00 plus Edge 40 × 1/2 = 20.00 = 60.00, then a fourth attempt is 409 `ATTEMPT_LIMIT_REACHED`;
  - correct Python: 40.00 + 40.00 = 80.00, two criterion rows.
- **Mutation-checked, each failing at least one test:** test weights counted as 1 (the overflow case: 64.00 instead of 53.33); a hidden result always `passed`.
- **Not in this row:** manual scoring and recompute (5.6e), restart recovery (5.6d), 403/masking matrices (5.6c).

## 5.6c — Authorization and masking tests

- **Most single cases of the row already existed** (5.1c's deadline and attempt tests, 5.5a's 403 for another student and its key sets, the 3.6b sweep). What was missing was the Phase 5 counterpart of 3.6a: one table of who may call what, checked the same way for every endpoint, and a guard so a new endpoint cannot ship without a row. That is what this adds; the single-case tests stay where they are.
- **`SubmissionAuthorizationMatrixTests` is a separate class from `AuthorizationMatrixTests`,** not extra rows there: the callers differ (author and classmate are both enrolled, so "enrolled student" is no longer one actor) and the worlds differ (submissions, results and grades). It follows the same structure on purpose: rows with a status per caller, three checks per row, a completeness guard.
- **Six callers:** owner, other teacher, author, classmate, outsider (a student not in the course), admin. **Rows (11):**
  - submitting, once per state: open (students of the course 202, outsider 404, teachers and admin 403), draft (404 for every student), past deadline (409 for students of the course, still 404 for the outsider), attempt used (409 for the author, 202 for the classmate, whose count is their own);
  - `GET /submissions/{id}`: author 200, owner and admin 200, classmate and outsider 403 (5.5a's decision), other teacher 404;
  - `GET .../submissions` (teachers; a draft's too), `GET .../my-submissions` and `GET .../my-grade` (students; a draft is 404);
  - `PUT .../manual-scores/{criterionId}` (owner and admin 200, other teacher 404, every student 403).
- **A 409 counts as a refusal in "changes nothing":** a late or over-limit call must not leave a submission row. The snapshot covers courses, enrollments, assignments, rubric, tests, submissions, results, grades and criterion scores.
- **The worlds for submitting have no test cases,** so an accepted submission is graded without starting a container and the matrix needs no Docker.
- **Raw JSON, as in 3.6b:** the student's submission view has exactly the 13 documented keys, its criteria `{name, type, score, maxScore, comment}` (no id, no weight), its hidden result `{id, name, visibility, position, passed}`; the student's list rows and `best` have only their keys; none of five markers that exist only inside the hidden test and its result is in anything a student gets. Positive control: the teacher's view holds all of them and has `studentId`. A student's own list holds only their own submission, never the classmate's.
- **The multipart upload shares its row.** The guard keys on method and path, which the JSON and multipart `POST` share, so a separate test sends a real multipart body as each caller (five of them; the author is the 202 case in the status row) and expects the open row's statuses and no change on a refusal.
- **Mutation-checked, each failing at least one test:**
  - the ownership check removed (5 failures);
  - the deadline check removed (5);
  - the attempt limit removed (3);
  - the student's own list not filtered by student (2).
- **Tests (+239, backend 1975 -> 2214; ai-service and frontend unchanged and passing).**

## 5.6d — Restart recovery and no lost submissions when execution throws

- **`RealHttpSubmissionWorkerTests` (5.2a-c) already tests the mechanics with a stand-in processor** that holds and throws on command. What it cannot show is what the real grading leaves behind when it fails or is cut short: whether the writer stores half a result, whether a recovered submission's old grade is replaced, whether a teacher's score survives. 5.6d tests those through the real chain.
- **Only the sandbox is replaced, no Docker.** `ScriptedSandbox` (a `@Primary SandboxRunner` in a `@TestConfiguration`) reads the program text: `CRASH` throws an `IllegalStateException`, `STACK` a `StackOverflowError` (an `Error`, which the worker rethrows after recording `ERROR`), `SANDBOX` returns a `SANDBOX_ERROR` result (so `SubmissionTestRunner` throws `SandboxFailedException`), anything else prints `1`. The 4.x tests own what a real container failure looks like; here a failure only has to reach the worker. The test therefore runs anywhere the other `RealHttp` tests run.
- **The restart is the two hooks called by hand,** as `RealHttpSubmissionWorkerTests` does: a second Spring context on the same database would run the startup sweep against Docker and a second worker pool against the first, which is not a restart. The limit is stated, not hidden: the test shows what the hooks do to the database, not that Spring calls them (the bean types are `SmartInitializingSingleton` and `ApplicationRunner`, which the compiler checks).
- **Tests (+7, backend 2214 -> 2221; ai-service and frontend unchanged and passing), totals by hand (TESTS 60 over two tests of weight 1, one passes: 60 × 1/2 = 30.00; Style 40 manual):**
  - three failure kinds (sandbox failure, unexpected exception, `Error`): `ERROR`, source and attempt number kept, `started_at` and `finished_at` set, no `test_results`, `grades` or `criterion_scores`, the student's view says `ERROR` with no grade and no tests, and the next submission is accepted (the single attempt was given back, 5.1d) and graded 30.00;
  - a burst of 12 (every third good, the others failing in turn in the three ways): all end `GRADED` or `ERROR`, none `QUEUED`/`RUNNING`, code intact, attempts 1 to 12, good ones graded 30.00, failed ones with nothing stored;
  - a restart with a `QUEUED` and a `RUNNING` good submission (the latter with a stale partial result), a `RUNNING` one that fails again, a `QUEUED` one that crashes, and a `GRADED` one: the good two end `GRADED` with exactly two results (replaced, not three), the failing two end `ERROR` with nothing stored, and the graded one is untouched (`finished_at` unchanged, no results);
  - a crash after the grade was committed: graded 30.00, teacher scores Style 10 (total 40.00), the row is set back to `RUNNING` with a wrong grade (99.00) as the crash would leave it, then recovered: one grade of 40.00 (30.00 recomputed + the teacher's 10.00), two results, two criterion rows, the teacher's score and comment kept, not provisional;
  - recovering with nothing left changes no row.
- **Mutation-checked, each failing at least one test:** the worker not recording `ERROR` (5 failures); `RUNNING` rows not requeued (2); the old grade not deleted when regrading (1). Dropping a stale partial result is done twice (step one of recovery and `SubmissionResultWriter.store`), so removing only step one is not seen by a test: the writer's replacement covers it, as 5.4b intended.
