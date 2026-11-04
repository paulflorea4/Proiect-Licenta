# Decisions / Assumptions — Phase 8 (`docs/tasks/tasks-phase-08.md`)

Historical, append-only. One entry per commit where a non-obvious call was made — a gap in the task spec, a gate resolution, a formatter/tool choice, a bug found and fixed. Not read every session — see the note in root `CLAUDE.md`. Load this only when auditing a specific Phase 8 decision.

## 8.0a — Numeric values for enum fields are a 400 (review, low)

- **The finding was real (the review had not run it):** before the fix, `{"role":2}` was accepted as `ADMIN` (a numeric value a 200, not the 400 that 2.5b documents), `"type":0` stored a `TESTS` criterion, and `"visibility":0`/`1`/`"1"` were `PUBLIC`/`HIDDEN`; the new test rows failed 10 times against the unchanged code.
- **The switch moved in Jackson 3:** `FAIL_ON_NUMBERS_FOR_ENUMS` is an `EnumFeature` in `jackson-databind` 3.1 (checked with `javap`), no longer a `DeserializationFeature`, so the property from the 3.4a suggestion (`spring.jackson.deserialization.fail-on-numbers-for-enums`) would not bind to anything. Spring Boot 4.1 exposes `EnumFeature` as `spring.jackson.datatype.enum.*` (its configuration metadata), so the setting is `spring.jackson.datatype.enum.fail-on-numbers-for-enums: true`, in the common part of `application.yml`.
- **For every enum of every body at once:** one mapper setting, not a check per field. The three enum fields in request bodies today are `RoleChangeRequest.role`, `RubricCriterionRequest.type` and `TestCaseRequest.visibility`; a number or a numeric string in any is 400 `MALFORMED_REQUEST` (Jackson's read error, as any unreadable body), and nothing is stored.
- **Not done here:** the twin finding in the same 3.4a suggestion (a fractional number accepted for an integer field and truncated, `accept-float-as-int`) is not this row.
- **Tests (+11, backend 2551 -> 2562; ai-service 985 and frontend 3 unchanged and passing):** `RealHttpAdminRoleTests` four more bodies (`0`, `1`, `2`, `"2"`), each 400 with the role unchanged; `RealHttpRubricTests` `"0"` among the unknown types and a new test for `0` and `1` (400 `MALFORMED_REQUEST`, nothing stored); `RealHttpTestCaseTests` `0`, `1`, `"1"` among the refused visibilities; `ApplicationConfigTests.numbersAreNotAcceptedForEnumFields` pins the YAML value.
- **Mutation-checked:** the same tests without the setting fail 10 times (the run before the fix).

## 8.0b — A CORS refusal is the standard JSON error body (review, low)

- **Where the plain text came from:** Spring's `DefaultCorsProcessor.rejectRequest` sets 403 and writes `Invalid CORS request`. Spring Security's `CorsConfigurer` (7.1) has no way to set the processor (only a configuration source or a preflight handler; checked with `javap`), and the filter it adds is `new CorsFilter(corsConfigurationSource)`.
- **So `SecurityConfig` builds that filter itself:** the same `CorsFilter`, from the same `corsConfigurationSource` bean, with `setCorsProcessor(new JsonCorsProcessor(writer))`, added with `http.addFilter(...)`, which puts a `CorsFilter` at the position Spring Security registers for it (where `http.cors(...)` put it), ahead of authentication. No new bean: a `corsFilter` bean would also be picked up, but Boot would register it as a servlet filter a second time. The parameter is named `corsConfigurationSource` so the right bean is injected (MVC's `HandlerMappingIntrospector` is a `CorsConfigurationSource` too).
- **`security/JsonCorsProcessor`** extends `DefaultCorsProcessor` and overrides only `rejectRequest`, writing 403 `ACCESS_DENIED` through the existing `JsonErrorWriter` (the security chain's other refusals use it too). The origin, method and header checks are Spring's, unchanged. The `Vary` headers the check adds go to the servlet response before the refusal, so they stay (asserted).
- **Tests (+1, backend 2562 -> 2563; ai-service 985 and frontend 3 unchanged and passing), `RealHttpCorsTests`:** every refusal case (six other origins, an unlisted method, an unlisted header, a call with a valid token from another origin) now also asserts JSON content type, `status` 403, `code` `ACCESS_DENIED`, the fixed message, no `Invalid CORS request`, and `Vary: Origin`; a new test sends a signup from another origin: refused in the standard shape and no user created. The preflight and allowed-origin tests pass unchanged, so the filter's position is the same.
- **Mutation-checked:** without the processor, 9 of the 17 CORS tests fail.

## 8.0c — An oversized multipart body elsewhere is `REQUEST_TOO_LARGE` (review, low)

- **Why the handler cannot just look at the controller:** the multipart limit fails while `DispatcherServlet` parses the body (`resolve-lazily` is false, and stays so: lazily, an oversized body to an endpoint that never reads it would not be refused at all), before a controller method is chosen. So `handleMaxUploadSizeExceededException` asks Spring MVC's own `RequestMappingHandlerMapping` which method the request is addressed to (path, method and content type, as dispatch would): a method with a `MultipartFile` parameter (or a list of them; today only `SubmissionController.submitFile`) keeps `SOURCE_TOO_LARGE`; anything else, including no method at all (the lookup throwing or returning nothing), is the generic `REQUEST_TOO_LARGE`. No path is repeated in the handler.
- **Injected lazily:** `ObjectProvider<RequestMappingHandlerMapping>` in a new constructor, because the mapping is built from the controller advice beans, this class included; `ErrorPiecesTests` passes an empty provider (then nothing is an upload).
- **Found while testing:** an anonymous oversized upload is a 401, not a 413 (the security chain runs before MVC parses the body), so the test of the submission endpoint keeping `SOURCE_TOO_LARGE` uses a signed-in teacher (wrong role, still 413 first, as 5.1b documents). Signup, which is public, gets the 413.
- **Tests (+6, backend 2563 -> 2569; ai-service 985 and frontend 3 unchanged and passing), `RealHttpSubmitFileTests`:** a 1.5 MB multipart body to `POST /courses`, `POST /auth/signup` (no token), `PUT /assignments/1`, a path that does not exist and `GET /assignments/1/submissions` is 413 `REQUEST_TOO_LARGE` with nothing about source code; to the submission upload by a teacher it is still `SOURCE_TOO_LARGE` (the student's case was already tested).
- **Mutation-checked:** always answering `SOURCE_TOO_LARGE` (the old behaviour) fails the 5 new rows.
- **Environment:** Docker Desktop stopped again mid-session (the first run of these tests could not start a context); restarted with the stale-socket step. Filed in `docs/suggestions.md`.

## 8.0d — An email longer than 255 characters after lower-casing is a 400 (review, low)

- **Reproduced first:** a valid address of exactly 255 characters as typed, with 64 `İ` (U+0130) in its local part, passed `@Size(max = 255)` and failed the `VARCHAR(255)` insert with a 500: `toLowerCase(Locale.ROOT)` turns `İ` into `i` plus a combining dot, 319 characters in all.
- **Normalize before validating:** `SignupRequest`'s compact constructor, which already trimmed the email, now also lower-cases it with `Locale.ROOT`, so every constraint (`@Size`, `@Email`, `@NoNul`) sees the stored value and an over-long one is the usual 400 `VALIDATION_FAILED` naming `email`. `AuthService.normalizeEmail` stays (signin and other callers use it; on a lower-cased value it is a no-op). The DTO does not call the service (no `dto` -> `service` dependency); `dto/SignupRequestTests` checks the two give the same result for plain, spaced, `İ`, `ß`, Greek-final-sigma and no-break-space inputs.
- **Signin not changed:** an over-long email there only fails to match (401, no insert), so nothing reaches a column.
- **Tests (+11, backend 2569 -> 2580; ai-service 985 and frontend 3 unchanged and passing):** `SignupValidationTests` +3 (the 255-character `İ` address is 400 `VALIDATION_FAILED` on `email` with no user created; the same shape with `I`, which does not lengthen, is created at exactly 255; a short `İnci@example.com` is stored lower-cased as before); `SignupRequestTests` (8: the six normalization pairs, `İ` becoming two characters, a missing email left for `@NotBlank`). The existing "longer than the column" test sends a 250-character local part, which `@Email` refuses before the size is checked, so it never covered this.
- **Mutation-checked:** before the fix the new validation test got the 500.
- **Second commit, the backend CI failure on PR #172 (unrelated to this row):** `SandboxHostileProgramTests.aForkBombThatDiesOnTheFirstRefusal...` got an accepted status but took 24.0 s against its 20 s bound (10 s limit + 2 s start grace + 8 s `KILL_ALLOWANCE`). The clock wrapped the whole call, which includes removing a container that still holds about 127 forked processes, slow on a busy runner. Both fork-bomb tests now hold the run itself (`result.runtime()`, our kill included) to the old bound, and the whole call, removal included, to that plus `FORK_BOMB_CLEANUP_ALLOWANCE` (30 s, a test constant); `nothingIsLeftBehind` still checks for leftovers after every test. No sandbox limit changed. Same family as PR #151's fork-bomb fix (4.7a's rule: a hostile outcome that depends on the host accepts each).

## 8.0e — A deadline at most 5 years ahead (GATE, the human's value; review, low)

- **The value is the human's: 5 years ahead** (answered in chat on 2026-10-30, between a fixed year 9999 and a moving 5-year window; the window also catches a typo like 2205). `AssignmentRequest.DEADLINE_MAX_AHEAD = Period.ofYears(5)`, next to the other assignment maxima.
- **The check:** `AssignmentService.requireDeadlineInRange`, which now holds both deadline rules: not after the clock is `DEADLINE_NOT_IN_FUTURE` (as before); after `now + 5 years` (calendar years at UTC on the injected `Clock`, so a leap day or a time zone does not move it; exactly at the limit is allowed) is the new 400 `DEADLINE_TOO_FAR` (`ErrorCode`, `DeadlineTooFarException`; the deadline is not echoed). On update, as before, only a deadline that changes is checked, so an assignment stored with a further deadline (seed, SQL) stays editable.
- **Reproduced:** a deadline of `+300000-01-01T00:00:00Z` (past what `timestamptz` stores, up to the year 294276) was a 500 `INTERNAL_ERROR` at the insert; with the check removed, the new test got exactly that.
- **Not a field constraint:** the window depends on the clock, so it is a service rule like the not-in-future one, not an annotation.
- **Tests (+8, backend 2580 -> 2588; ai-service 985 and frontend 3 unchanged and passing):** `AssignmentServiceTests` +3 (the value; exactly 5 years ahead accepted; 1 ms later refused, nothing stored); `RealHttpAssignmentCreateTests` +3 (year 300000 and year 9999 are 400 `DEADLINE_TOO_FAR` with nothing stored or echoed; 6 years ahead refused and 4 accepted); `RealHttpAssignmentChangeTests` +2 (a changed deadline in year 300000 is refused and nothing changes; an unchanged deadline 10 years ahead does not block other edits).
- **Mutation-checked:** without the too-far check, 5 tests fail (the year 300000 back to the 500).
- **Sample bodies updated:** `AuthorizationMatrixTests` and `RoleMatrixIntegrationTests` sent a fixed deadline of `2999-01-01`, now refused (the first full run failed 3 authorization-matrix rows with `DEADLINE_TOO_FAR`; the role matrix passed only because its sample paths do not exist). Both now compute a deadline a year ahead, valid whatever the date the tests run.

## 8.0f — Deadlines truncated to microseconds when received (review, low)

- **Truncate, not round, in the request record's constructor** (`AssignmentRequest`, next to the title trim): the deadline is cut to microseconds before validation and before the service compares it with the stored one, so create and update share it and nothing else needs to know. Postgres `timestamptz` rounds, so rounding in the application would be the same rule as the database's, but truncation is what the review asked for and keeps the stored value never later than the one sent; a deadline `...123456999` becomes `...123456`, not `...123457`. A null deadline is left for `@NotNull`.
- **Reproduced:** an assignment whose stored deadline is in the past, then a `PUT` echoing it with 999 extra nanoseconds: 400 `DEADLINE_NOT_IN_FUTURE` (the update re-validated it because `Instant.equals` saw a change); with the truncation it is a 200 and the stored value is unchanged.
- **Not changed:** the update's "only a changed deadline is checked" rule (3.3c); the microsecond precision of `updated_at`/`submitted_at` (those come from the `Clock`, already cut).
- **Tests (+4, backend 2588 -> 2592; ai-service 985 and frontend 3 unchanged and passing):** `AssignmentRequestTests` (3: nanoseconds cut not rounded, microseconds unchanged, null stays null), `RealHttpAssignmentChangeTests` +1 (the echo above).
- **Mutation-checked:** without the truncation, the unit test and the real-HTTP test fail (the latter with the 400 above).

## 8.0g — An appended test position never exceeds `POSITION_MAX` (review, low)

- **Clamp, not a second cap on the stored value** (the review offered "clamp, or apply the cap to both paths"): `TestCaseService.create` uses `min(nextPosition, TestCaseRequest.POSITION_MAX)` when the request gives no position. Raising the cap on the append path would not do, since the request's `@Max` is what refuses an echoed `100001`, and loosening that would let a request name any position.
- **Order survives the clamp:** tests with equal positions run in id order (`findByAssignmentIdOrderByPositionAscIdAsc`), and an appended test always has a higher id, so the second test appended at the cap still runs after the first.
- **Not changed:** `TestCaseRepository.nextPosition` (still `max + 1`, and still the right answer below the cap) and update, which keeps the stored position when none is sent.
- **Reproduced:** a test stored at 100000, one appended without a position got 100001 (201), and a `PUT` sending that position back was 400 `VALIDATION_FAILED` on `position` ("must be less than or equal to 100000").
- **Tests (+2, backend 2592 -> 2594; ai-service 985 and frontend 3 unchanged and passing):** `RealHttpTestCaseTests` +2 (two tests appended after one at the maximum both get 100000 and list in creation order; the position the server returned is sent back in a `PUT` and is a 200).
- **Mutation-checked:** without the clamp both fail, the second with the 400 above. The first draft of the echo test sent the constant `POSITION_MAX`, not the returned position, and so passed without the fix; the mutation run showed it, and it now sends what the server answered.
- **Second commit, the backend CI failure on PR #175 (unrelated to this row):** `SandboxHostileProgramTests.aForkBombThatDiesOnTheFirstRefusalIsStoppedByTheProcessMemoryOrTimeLimit` got an accepted status but its run's `runtime()` was 20.27 s against a 20 s bound (10 s limit + 2 s `START_GRACE` + 8 s `KILL_ALLOWANCE`); the only failure of 2594. `runtime()` includes our `docker kill`, which on a container holding about 127 forked processes took about 8.3 s on a busy runner. 8.0d (PR #172) moved the container's *removal* to its own allowance but left the kill under the old one. `assertForkBombStoppedInTime` now uses `FORK_BOMB_KILL_ALLOWANCE` (16 s, a test constant) in place of `KILL_ALLOWANCE`; the wall-clock bound that adds `FORK_BOMB_CLEANUP_ALLOWANCE` follows from it. No sandbox limit changed, and a bomb that is never stopped still fails the bound. Third time this test's timing has been widened (#151, #172, #175): see `docs/suggestions.md`.

## 8.0h — A hint holding NUL is a bad answer, never a 500 at insert (review, low)

- **Refuse, do not replace** (the task row's wording, over the review's other option of replacing it as `SubmissionGrader.storable` does): a NUL in a model's hint says the answer is broken, and a retry usually gives a clean one, whereas silently substituting a character would show the student a hint the model did not write. Test results are different: there the text is the program's own output and must be kept.
- **Both sides, because each could be the one that lets it through.** `ai-service/feedback.py` `parse_hint` raises `LlmBadResponse("The model's hint contains a NUL character")` after the blank check (so the existing "control-character hint" row, only NUL and BEL, still says blank); it is retried once like any bad answer and then a 502, which the backend already maps to 503 `HINTS_UNAVAILABLE`. `AiServiceClient.feedback` treats a hint with U+0000 as "The AI service's answer could not be read" (status null), so an AI service that did not check, or a future provider, cannot reach the insert; the backend does not retry (Phase 16).
- **Only the hint is checked.** It is the one model-written text that is stored. `fallback` is a boolean, and a NUL in the student's code is already refused at submission (`SOURCE_NOT_TEXT`, 5.1c).
- **Reproduced:** with the mocked AI service answering `{"hint": "Look at the <U+0000> operator.", "fallback": false}`, `POST /submissions/{id}/hints` was a 500 `INTERNAL_ERROR`; with the check, 503 `HINTS_UNAVAILABLE` and no `ai_feedback` row.
- **Tests (+12; ai-service 985 -> 994, backend 2594 -> 2597, frontend 3 unchanged and passing):** `test_malformed_output.py` +2 catalogue rows (a NUL inside, a NUL at the end), which the parametrized tests run for refusal, retry-then-good, two-bad-then-error, plus the say-why test and the endpoint's 502 (9 tests; `REFUSALS` is now the set of fixed messages a refusal may use); `AiServiceClientTests` +2 rows (NUL inside, NUL alone, which `isBlank()` does not call blank); `RealHttpHintOutgoingRequestTests` +1 (the end-to-end 503 above, nothing stored).
- **Mutation-checked:** without the AI service check, the 9 new ai-service tests fail; without the backend check, the 2 client rows and the end-to-end test fail (the latter with the 500).
- **Tooling note:** a first edit script wrote a real NUL byte into `feedback.py` (an escape in a comment was collapsed before Python read it); caught by `git diff` showing the file as binary, restored, redone without escapes in the source. Final files contain no NUL byte.

## 8.0i — Deep JSON and an odd `promptFeedback` are `LlmBadResponse` (review, low)

- **Three parse sites, not one.** The review named the model's answer; the same `RecursionError` (not a `ValueError`, so uncaught) comes from `json.loads` in `parse_hint`, from `response.json()` on Gemini's 2xx body in `GeminiProvider.generate`, and from `response.json()` in `_status_word` on a non-2xx body (a deep error body turned a 503 into a 500). All three catch it: the first two as `LlmBadResponse` (retried once, then a 502, like any other bad answer), the third by falling back to the bare status, as for an error body without a status. Reproduced with 100,000 nested brackets (the parser stops at about a thousand levels): `RecursionError: Stack overflow` three times. No message was added: the existing fixed texts say "not a {hint} object" and "not JSON", true enough for a body we cannot read.
- **`promptFeedback`: absent is fine, anything but an object is a bad answer.** Before, `(body.get("promptFeedback") or {}).get(...)` raised `AttributeError` for a non-empty list, string or number. Now `None` (or no key) passes, a dict is read for `blockReason` as before, and any other value is `LlmBadResponse("Gemini's answer has an unexpected shape")`. This is stricter than before for the falsy odd values (`[]`, `""`, `0`, `false` used to be treated as absent); Gemini sends an object or nothing, so a bad shape is worth a retry rather than a silent pass. Not tested for those four, only for the truthy ones that crashed.
- **Not changed:** the retry rules, `MAX_ATTEMPTS`, the hint's own checks (blank, NUL), and the backend, which sees only the AI service's own `{hint, fallback}` answer.
- **Tests (+15, ai-service 994 -> 1009; backend 2597 and frontend 3 unchanged and passing):** `test_malformed_output.py` +2 catalogue rows (a deeply nested array and object, the marker innermost), run for refusal, retry-then-good and two-bad-answers-then-error (6 tests); `test_llm.py` +9 (four odd `promptFeedback` values; a deep 2xx body is a bad response; a deep 503 body still gives `Gemini answered HTTP 503` with its status; three `promptFeedback` values that block nothing, `null`, `{}` and an object without `blockReason`, are no obstacle, so the stricter rule cannot over-refuse).
- **Mutation-checked:** with the source files back at their old versions, 12 of the new tests fail (the three "no obstacle" cases pass either way, by design).

## 8.0j — A real-HTTP test of the container error path (review, low)

- **Which requests reach `/error`, found by probing rather than assumed.** A throwaway probe sent 15 malformed targets over a raw socket, as anonymous and with a bad token. Two families came out: the ones **Spring Security's `StrictHttpFirewall` refuses** (a semicolon, a doubled slash, `..` or `%2e`, an encoded `%25`) are a 400 in the standard JSON body, so they do go through `sendError` and `ApiErrorController`; the ones **Tomcat's own parser refuses** (`%2f`, `%5c`, `%00`, a bare `%` in the path, a backslash, a non-ASCII byte, a 70,000-character URL) are answered by Tomcat with its HTML page and never reach the application. `?x=%` in the query is not refused at all (401, as any anonymous call). The probe was deleted.
- **The test covers the first family and pins the second only loosely.** The task row asks for the JSON shape, and `GET /auth/me;x` is the first case. For Tomcat's refusals the contract is only that they stay a 400 and show no application text (`Exception`, the package name), with the gap named in the Javadoc and filed in `docs/suggestions.md`: making them JSON would be a Tomcat configuration (an error report valve), a new surface, not part of this row.
- **Callers do not matter, and the test says so:** the firewall runs ahead of the authorization rules, so a protected path (`/auth/me;x`) is a 400, not a 401, and the body is byte-identical for anonymous, a valid token, a bad token and a `POST`.
- **`GET /error` itself** is public (2.4a) and, with no recorded error, is the generic 500 in the standard body (what `ErrorPiecesTests` pins on the controller); the real-HTTP row confirms the public path answers it. Odd for a client to receive a 500 for asking, but it is the documented behaviour, so it is pinned, not changed.
- **No production change.** Tests only (+25, backend 2597 -> 2622; ai-service 1009 and frontend 3 unchanged and passing).
- **Mutation-checked:** with `@RestController` removed from `ApiErrorController` (so Spring Boot's own error controller answers), 18 of the 25 fail (the Tomcat-level and status-only rows do not depend on it, by design).
- **A slip of mine, caught by the first run:** I first listed `/auth/me?x=%` among the Tomcat refusals; the probe had shown it is a 401 (the query is not decoded eagerly). Removed.

## 8.0k — Token verification pins HS256 and requires `exp` (review, low)

- **Pin in the parser, not after it.** `Jwts.parser().verifyWith(key).sig().clear().add(Jwts.SIG.HS256).and()`: the parser then has one algorithm to try, and any other `alg` header (HS384, HS512, `none`) is an `UnsupportedJwtException` or a signature failure, which `parse` already turns into empty. The alternative, comparing `getHeader().getAlgorithm()` after a successful parse, would let the parser verify a signature the service never meant to accept. Reproduced first: an HS384 token signed with our own secret (48 bytes, long enough for HS384; jjwt's `signWith(key)` picks it) verified.
- **`exp` is required by the service, not the parser.** jjwt checks `exp` only if the claim exists, so an HS256 token with the right key and no expiry verified for ever. `parse` now returns empty when `claims.getExpiration()` is null (the check sits with the other missing-claim checks; `issue()` always sets one, so no token we issue is affected). Not required: `iat`, `nbf`, and an upper bound on `exp` (a token with an `exp` 100 years ahead and our key is still ours; the key is the trust, this row is defence in depth).
- **The misleading test, as the review said:** `parseRefusesATokenSignedWithADifferentAlgorithm` signed HS512 with `SECRET + SECRET`, a different key, so it failed on the signature and said nothing about the algorithm. It is now `...AndOurOwnKey` with HS384 (the strongest the 48-byte key allows; HS512 needs 64 bytes), next to `parseAcceptsHs256WithOurKeyAsTheBaselineOfThatTest` and the old HS512/another-key case kept under its own name. The `signed()` helper used by the claim tests had been signing HS384 (the same key-size default), so its "is present" case would have failed after the pin; it now names HS256.
- **Tests (+3, backend 2622 -> 2625; ai-service 1009 and frontend 3 unchanged and passing):** all in `JwtServiceTests` (21, was 18). No real-HTTP row: `RealHttpJwtFilterTests` already runs the filter with real tokens and passes unchanged.
- **Mutation-checked, each guard on its own:** without the pin, the HS384/own-key test fails; without the `exp` check, the no-expiry test fails.
- **Closes the review block.** 8.0a-8.0k are all Done; Phase 8's frontend rows (8.1a onwards) are next.

## 8.1a — The `/frontend` app is still current (first frontend row)

- **Nothing re-scaffolded, as the row's note asks.** `/frontend` is the 0.1d `npm create vite` React+TS output (`App.tsx`, `main.tsx` unmodified) with the 0.1f test runner (Vitest, Testing Library, jsdom, `src/test/setup.ts`, `App.test.tsx`) and 0.5c's ESLint flat config and Prettier. Node is 24.19 (the Defaults' Node 24), npm 11.17.
- **Verified, not assumed:** `npm ci` from the lockfile, `npm run build` (`tsc -b` then `vite build`), `npm test` (3), `npm run lint`, `npm run format:check`, and the dev server itself (started on a spare port, `GET /` 200 with the `#root` element and `/src/main.tsx` 200, then stopped by its port). The working tree stayed clean (`dist/` is ignored).
- **"Current" also meant the dependencies: the gate was asked, not assumed.** `npm audit`: 16 vulnerabilities, all in devDependencies (`npm audit --omit=dev`: 0, so nothing reaches the browser bundle). 3 high: `vite` 8.3.0-8.3.2 (dev-server issues, among them launch-editor command handling on Windows and files outside `server.fs.allow`), transitive `undici` and `zod`; 13 moderate in the ESLint chain. A plain `npm audit fix` (not `--force`) fixes the 3 high within the declared ranges, lockfile only. Changing installed versions is external software, so it went to the human (root `CLAUDE.md` gate), with three options; **the human chose to apply it in this row**. Result: 35 lockfile entries moved (npm reports 21 packages; the rest are rolldown's optional per-platform binaries), every one a dev dependency: `vite` 8.3.2 -> 8.3.5, `rolldown` 1.2.12 -> 1.2.16 (and its bindings), `undici` 8.11.2 -> 8.12.3, `zod` 4.6.5 -> 4.6.7, `eslint` 10.12.0 -> 10.14.0, `typescript-eslint` (and its packages) 8.71.1 -> 8.72.1, `postcss` 8.5.32, `nanoid` 3.3.20, `@oxc-project/types` 0.156.0. `package.json` unchanged.
- **Left as they are: the 13 moderate findings in the ESLint chain** (`eslint`, `@eslint/js`, `typescript-eslint` and its parts, `eslint-config-prettier`, `eslint-plugin-react-refresh`, `minimatch`). npm's only "fix" is a semver-major *downgrade* (`typescript-eslint` 8.14, `eslint-config-prettier` 6, `@eslint/js` 9), not an upgrade; lint tooling never ships. Filed in `docs/suggestions.md`.
- **Re-verified after the fix,** from a clean `npm ci` of the new lockfile: build, tests, lint, format, and the dev server on vite 8.3.5. Backend and ai-service suites run as usual (no change there).
- **Not done:** no `npm outdated` upgrades beyond what the audit fix moved (e.g. TypeScript 7, `@types/node` 26 are majors; React 19.3.1 is a patch the fix did not need). Those are dependency changes for a task that asks for them.

## 8.1b — `react-router` with placeholder routes

- **The dependency was asked for (gate):** `react-router` ^8.5.1, the human's choice among 8.5.1, 8.4.x or not yet (asked in chat with its licence, peers and age: 8.5.1 published 2026-10-27, MIT, peers `react`/`react-dom` >= 19.2.7, which ours satisfy). It brings `cookie-es` 3.1.1 and `@remix-run/route-pattern` 0.22.1. `npm audit --omit=dev`: still 0. In v8 the package is `react-router` alone (no `react-router-dom`), and `BrowserRouter`, `MemoryRouter`, `Routes`, `Route`, `Link` are exported from it (checked in the installed package before writing code).
- **Declarative mode, router outside `App`.** `main.tsx` wraps `App` in `BrowserRouter`; `App` is only the `<Routes>`. So a test renders `App` in a `MemoryRouter` at any path, and a later route guard (8.3c) wraps elements inside `App` without touching the router. The data-router mode (`createBrowserRouter`, loaders) was not used: nothing loads data yet, and 8.2a's API wrapper is the agreed way to fetch, not route loaders.
- **First frontend conventions (the frontend `CLAUDE.md` said they are set by the first real components):** pages in `src/pages/`, one component per file named `<Name>Page.tsx`, a plain `function` with `export default` as the template's `App` does; a page's root is `<main>` with one `<h1>`; tests stay next to the code. Each placeholder names, in a comment, the row that will fill it (8.3a, 8.3b, 8.4a).
- **One addition the row does not list: a catch-all not-found page** (`path="*"`), with a link back to `/`. Without it an unknown URL rendered an empty page. Small and needed by any later route; easy to drop if unwanted.
- **No navigation menu:** 8.5a builds the header and role-aware menu; until then the routes are reached by URL (and the not-found link).
- **The Vite template's demo removed:** `App.tsx` was the counter/hero page; its `App.css`, `src/assets/` (hero.png, react.svg, vite.svg) and `public/icons.svg` were referenced only by it and are deleted (checked with a search first). `index.css` (global variables, fonts, `#root` layout) and `favicon.svg` stay; styling is not this row's business.
- **Deep links:** `BrowserRouter` needs the server to answer every path with `index.html`. Vite's dev server does (checked: `/`, `/signin`, `/signup`, `/courses`, `/nothing-here` all return the app shell). Whatever serves the built `dist/` in Phase 16 must do the same; filed in `docs/suggestions.md` so it is not lost.
- **Tests (frontend 3 -> 8; backend and ai-service unchanged and passing):** `App.test.tsx` replaces the template's counter tests: each of the four paths shows its own heading and only one `<h1>` (so no two routes render at once); `/nothing-here`, `/courses/extra` and `/SIGNIN/x` show not-found; clicking the not-found link shows the start page.
- **Mutation-checked:** with the `/courses` route removed, its test fails (1); with the catch-all removed, the three not-found cases and the link test fail (4).

## 8.2a — API client: `fetch` wrapper, typed helpers, the backend's error shape

- **`fetch`, not axios.** The row allows either; axios would be a new dependency (a gate) and buys nothing here: one base URL, JSON in and out, one error shape. No new dependency, lockfile untouched.
- **One module, `src/api/client.ts`:** `apiRequest<T>(path, {method, body, headers, signal})` and the shorthands `api.get/post/put/patch/delete<T>`. `T` is the caller's declared type, not checked at run time (there is no schema library, and adding one is a dependency); only the error body is checked, because the code branches on it.
- **Paths, not URLs:** the path must start with exactly one `/` and is appended to `VITE_API_BASE_URL`. An absolute URL or `//host` is refused before any request, so the wrapper can only call the Spring Boot API (the frontend never talks to anything else).
- **The base URL is read on every call, and a missing one is a configuration `Error`, not a silent default.** Vite does not load `.env.example`; with no `.env.local` the variable is undefined. Falling back to `http://localhost:8080` in code would make a production build without the variable quietly call localhost. Trailing slashes and surrounding spaces are dropped (`.env.example` says no trailing slash; tolerated anyway). The message names the fix (copy `.env.example` to `.env.local`). Typed in `src/vite-env.d.ts` (optional string).
- **Every failure is one `ApiError` (`status`, `code`, `message`, `fieldErrors`)**, carrying the 2.6a body as is, so pages branch on `code` as the backend's `ErrorCode` javadoc asks. Two client-side codes for what the backend cannot answer: `NETWORK_ERROR` (fetch rejected or the body broke off; `status` 0) and `UNEXPECTED_RESPONSE` (an error body not in the standard shape, such as Tomcat's own HTML 400 page that 8.0j filed, an empty body, or a 2xx body that is not JSON). The shape check is strict on what clients use: numeric `status`, non-empty string `code`, string `message`, `fieldErrors` absent or a list of `{field, message}` strings. The HTTP status is taken from the response, not the body. Messages for the client codes are fixed text, like the backend's, so `message` is always safe to show.
- **Abort is not an error to report:** if the caller's `signal` is aborted, the abort error is rethrown unchanged (so an effect cleanup can ignore it), not turned into `NETWORK_ERROR`.
- **Bodies:** a `FormData` is sent as is, without a `Content-Type`, so the browser sets the multipart boundary (the submission upload takes a file); anything else defined is JSON with `Content-Type: application/json`. Every request sends `Accept: application/json` (in the CORS allow-list with `Authorization` and `Content-Type`). An empty response body (204) returns `undefined`.
- **No auth in the wrapper yet:** the token can be passed in `headers` (`Authorization: Bearer ...`); 8.3b decides storage. The backend sends the token in the JSON body only, never a cookie, and CORS has `allowCredentials(false)`, so `credentials` is left at its default.
- **Bug found by the tests while writing:** the base-URL read sat inside the `try` around `fetch`, so a missing variable came out as `NETWORK_ERROR`; moved out of the `try`.
- **Tests (frontend 8 -> 47; backend and ai-service unchanged and passing):** `src/api/client.test.ts` stubs `fetch` (`vi.stubGlobal`) and the variable (`vi.stubEnv`): URL building and slash dropping, unset/blank variable and bad paths refused without a request, JSON and `FormData` bodies, headers and signal passed, each shorthand's method, parsed body and 204, the standard error with and without field errors, status from the response, eleven non-standard error bodies, network failure, a body that breaks off, abort, `isApiError`.
- **Mutation-checked:** error-body shape check off (9 failures), abort rethrow off (1), empty `code` allowed (1), trailing-slash drop off (1), `FormData` sent as JSON (1).

## 8.3a — Sign-up form wired to `POST /auth/signup`

- **Fields: full name, email, password, and no role** (the row: signup always creates a student; the backend's `SignupRequest` has no `role` and ignores one). The body sent is exactly `{fullName, email, password}`; a test asserts it and that the page has no select, radio, checkbox or role wording.
- **After success the page shows a confirmation with a link to Sign in**, instead of the form. Signup returns the user (`UserResponse`), not a token, so the user is not signed in; signing in is 8.3b's page, which this row leaves untouched. A redirect to `/signin` with a message is an equally good choice once 8.3b exists; easy to switch.
- **Checks in the page mirror the backend's limits only to answer sooner** (the backend enforces them): each field required (name and email: not only spaces), password at least 8 characters and at most 72 bytes UTF-8 (`PasswordPolicy`; `TextEncoder`, so `é` counts 2), name and email at most 255 characters. The limits are named constants in the page with a comment pointing at the backend; `PASSWORD_MIN_LENGTH` is the backend's placeholder pending the human's decision, so if it changes both must. The email format is left to the backend (Hibernate's `@Email` is lenient; a stricter check here could refuse an address the backend accepts); its field error is shown.
- **`noValidate` on the form**, so the browser's own bubbles do not replace the page's messages, and both kinds of error look the same: text under the field, `aria-describedby` + `aria-invalid`.
- **Backend errors:** `VALIDATION_FAILED` is mapped onto fields by `fieldErrors[].field` (first message per field); if none is a known field, the generic message is shown for the form. `EMAIL_ALREADY_USED` goes on the email field. Anything else (500, `UNEXPECTED_RESPONSE`, `NETWORK_ERROR`) is one `role="alert"` message with `ApiError.message`, which is fixed text. A new submit clears the old errors; the typed values stay.
- **The button is disabled while the request runs** (and reads "Creating account…"), so a double click sends one request.
- **No styling:** the styling convention is still unset (frontend `CLAUDE.md`); the page is plain semantic HTML on `index.css`. No shared form component either: one form so far; 8.3b can extract one if the sign-in form repeats it.
- **Tests (frontend 47 -> 68; backend and ai-service unchanged and passing):** `SignUpPage.test.tsx` (21) renders `App` at `/signup` with `fetch` and `VITE_API_BASE_URL` stubbed: fields and no role, the request and the confirmation, both links to sign-in, every client check (empty, spaces, 7/8 characters, 72/73 bytes, 36/37 `é`, 256-character name) without a request, email in use, backend field errors, unknown-field fallback, 500, a non-standard response, a network failure then a retry, the disabled button, errors cleared on resubmit.
- **Mutation-checked (8, each caught):** client checks off (7 failures), email-in-use mapping off (1), password measured in characters (1), button never disabled (1), a `role` sent (4), errors not cleared (7), unknown-field fallback off (1), name not trimmed for the empty check (1).

## 8.2b — Signin sets the JWT in an `httpOnly` cookie (new row)

- **Why the row exists (the human's decision at 8.3b):** 8.3b's row said to use an `httpOnly` cookie if Spring Boot sets one and otherwise to choose and document the fallback storage. 2.4a (the human's gate) had chosen "JSON body only, no cookie", so there was none. Asked in chat, with the trade-offs (XSS can read any token kept in script, whether in memory, `sessionStorage` or `localStorage`; a cookie needs CSRF protection and CORS credentials), the human chose the `httpOnly` cookie, the usual production recommendation. That reverses 2.4a. Five new rows were approved in chat: 8.2b (this one), 8.2c (the filter reads the cookie), 8.2d (CSRF: a custom header on cookie-authenticated state-changing requests, chosen over Spring's CSRF token), 8.2e (CORS credentials, signout) and 8.2f (frontend client). 8.3b's notes now record the storage as resolved.
- **The cookie (`security/AuthCookie`):** name `access_token`, `HttpOnly` (script never reads it), `Secure` (HTTPS only; Chrome and Firefox treat `http://localhost` as secure, so dev works over plain HTTP; Safari does not, see `docs/suggestions.md`), `SameSite=Strict` (never sent with a request a foreign site starts; the dev frontend `localhost:5173` and API `localhost:8080` are the same site, ports do not count), `Path=/`, `Max-Age` = the token's lifetime (`jwt.expiration`, 24h), no `Domain` (host-only, so a subdomain never gets it). Built with Spring's `ResponseCookie` and sent as a `Set-Cookie` header from the controller (`ResponseEntity`); the chain stays stateless (no session).
- **The token is out of the body:** `SigninResponse` is now `{expiresIn, user}`; `token` and `tokenType` are gone. Leaving the token in the body would let script read it from the response and defeat the cookie. The redacting `toString` went with the token (nothing secret is left).
- **Kept for now:** the filter reads only `Authorization: Bearer` until 8.2c, so between 8.2b and 8.2c a browser cannot yet use the cookie (no frontend uses it before 8.3b). The header stays accepted after 8.2c too: about 15 test classes authenticate with it, and a header cannot be attached by another site.
- **Not done here:** `__Host-` cookie prefix (browser-enforced `Secure`, `Path=/`, no `Domain`): the attributes already match it, but whether every dev browser accepts the prefix on `http://localhost` was not checked; filed in `docs/suggestions.md`. CORS credentials and signout are 8.2e.
- **Tests (backend 2625 -> 2628; ai-service and frontend unchanged and passing):** a test helper, `SigninCookies` (test package), reads the token from the signin's `Set-Cookie` (exactly one `access_token` header, or it fails). The tests that read `$.token` (or parsed `"token":"..."` from the body) now use it: `AuthFlowIntegrationTests`, `RealHttpCourseCreateTests`, `RoleMatrixIntegrationTests`, `RealHttpSeededGradingTests`, `RealHttpJwtFilterTests`, `RealHttpMeTests`, `RealHttpAdminRoleTests`. `SigninEndpointTests`: the body has no `token`/`tokenType`; the cookie's attributes (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`, `Max-Age=86400`, no `Domain`), one `Set-Cookie` only; the cookie holds a valid token that the body never repeats; the response is `no-store`; a failed signin (wrong password, invalid body) sets no cookie; the 2.4a test "the token is in the body only, not in a cookie" is replaced. `RealHttpAuthTests` checks the same attributes as Tomcat sends them over real HTTP, and still no `JSESSIONID`. The "no `Set-Cookie`" assertions on other endpoints (`/auth/me`, the filter, a failed signin in `AuthRejectionIntegrationTests`) are unchanged and still hold.
- **Mutation-checked:** `httpOnly(false)`, `secure(false)`, `SameSite=Lax` and no `Max-Age` each fail 2 tests (`SigninEndpointTests` with MockMvc, `RealHttpAuthTests` over real HTTP).

## 8.2c — The JWT filter also reads the token from the cookie (new row)

- **The row's rule, made exact (`JwtAuthenticationFilter.tokenOf`):** "the header wins when both are sent" is read as "**any `Authorization` header decides alone**". When the header is present, the bearer token is taken from it and the cookie is not looked at, even if that token is bad or the scheme is not `Bearer` (e.g. `Basic`). The request is then anonymous, not authenticated by the cookie. A silent fallback to a second credential would make which user a request runs as depend on whether the first one happened to verify. The cookie is read only when no `Authorization` header is sent at all, which is what a browser does after 8.3b.
- **Two `access_token` cookies give no token**, even when both are valid. A browser sends two of the same name when another host (a sibling subdomain setting `Domain=` on a parent) or another path planted one. Picking one would be guessing, so the request is anonymous (401 on a protected path, and the user signs in again). The `__Host-` prefix would stop the planting at the browser (`docs/suggestions.md`, 8.2b).
- **Only the exact name counts** (`access_token`, case-sensitive, as cookies are); a valid token under another name is ignored.
- **Nothing else changes:** the same `JwtService.parse`, the same principal and authorities, still stateless (no `Set-Cookie`, no session on a cookie-authenticated request). A bad cookie, like a bad header, only leaves the request anonymous, so public endpoints are not blocked by it.
- **CSRF:** cookie-authenticated writes are now possible and not yet protected beyond `SameSite=Strict`; 8.2d adds the custom-header check. CORS still has credentials off until 8.2e, so a cross-origin browser request does not send the cookie yet.
- **Tests (backend 2628 -> 2641; ai-service and frontend unchanged and passing):** `RealHttpCookieAuthTests` (new, 13, real HTTP so Tomcat parses the `Cookie` header):
  - with the cookie alone: a valid token authenticates (`/auth/me` names the user); the very cookie signin sets, sent back as a browser would, signs in; the token's role reaches the role rules (admin 200, student 403 on `/admin/users`); other cookies beside it don't matter; no `Set-Cookie` or session results;
  - a cookie that does not authenticate: a garbage, empty or tampered token is anonymous (401 `UNAUTHENTICATED`); a valid token under another name (`token`, `ACCESS_TOKEN`) is ignored; two auth cookies are refused (two users, or the same token twice); a bad cookie does not block `/health`;
  - header and cookie together: both valid gives the header's user; a valid header is not spoilt by a bad cookie; a bad bearer header or a `Basic` header is not rescued by a valid cookie.
- **Mutation-checked:** cookie never read (4 failures), cookie wins when both are sent (4), duplicates allowed (1), a non-bearer header falls through to the cookie (1).

## 8.2d — CSRF: cookie-authenticated writes need `X-Requested-With` (new row)

- **The rule (the human's choice at 8.3b: a custom header over Spring's CSRF token):** when the cookie authenticates a request whose method is not safe (RFC 9110's safe methods are `GET HEAD OPTIONS TRACE`; everything else counts, not only the four listed), the request must carry `X-Requested-With` (`AuthCookie.CSRF_HEADER`) with a non-blank value. Otherwise the answer is 403 in the standard body with a new code, `CSRF_HEADER_MISSING`, before routing, so nothing changes. **Why this works:** a form, link or image on another site can't add a header. Script on another origin can only send one after a CORS preflight, and the allow-list refuses that preflight for any origin not on it. `SameSite=Strict` already keeps the cookie off cross-site requests; this is the second, independent layer.
- **Any value, the name case-insensitive.** The defense rests on the header being present at all (it forces the preflight), not on its content. A specific value would add nothing; 8.2f's client sends one.
- **Where:** in `JwtAuthenticationFilter`, right after the token verifies, because only the filter knows that the credential came from the cookie (`tokenOf` reads it only when no `Authorization` header is sent). No new filter and no request attribute. The filter now gets `JsonErrorWriter`, the same writer as the other pre-MVC refusals. Its Javadoc names this as its one refusal.
- **What is exempt:**
  - **A bearer-authenticated request:** a browser never attaches an `Authorization` header by itself, so there is nothing to forge, and every existing test keeps working.
  - **A cookie that does not verify:** there is no credential to misuse, so it is simply anonymous (401 on a protected path, and a public signup still works).
  - **Safe methods.**
- **Public endpoints are covered too:** the rule follows the credential, not the endpoint. A valid cookie on `POST /auth/signin` without the header is 403. That blocks a login-CSRF attempt while the victim is signed in, and it will protect 8.2e's `POST /auth/signout` from a forced sign-out. The frontend always sends the header (8.2f), so it is never hit in normal use.
- **CORS:** `X-Requested-With` is added to the allowed headers, or our own frontend's preflight would fail. Credentials stay off until 8.2e.
- **Why a 403 with its own code, not "just anonymous":** treating the request as anonymous would give a 401 `UNAUTHENTICATED`, which a frontend reads as "sign in again". A distinct code tells a developer exactly what is missing.
- **Tests (backend 2641 -> 2653; ai-service and frontend unchanged and passing):** `RealHttpCsrfHeaderTests` (new, 11, real HTTP):
  - **Refused:**
    - a cookie-authenticated `POST /courses` without the header is 403 `CSRF_HEADER_MISSING` (JSON) and creates no course;
    - `PUT` and `DELETE` of a course and `PATCH` of a role are refused and change nothing;
    - a blank header doesn't count;
    - an unmapped path and signin are refused too.
  - **Allowed:**
    - with the header, any value: 201;
    - the header name matched in lower case;
    - `GET` and `HEAD` need no header;
    - a bearer write needs none, even with the cookie beside it;
    - a cookie that doesn't verify is just anonymous (401 on a protected write, public signup 201);
    - no credential at all is the usual 401.

  Plus `RealHttpCorsTests`: a preflight asking for `content-type,x-requested-with` is allowed.
- **Mutation-checked:**
  - check off: 4 failures
  - `DELETE` treated as safe: 1
  - bearer requests checked too: 3
  - a blank header counts: 1
  - header missing from CORS: 1

## 8.2e — CORS allows credentials; `POST /auth/signout` clears the cookie (new row)

- **CORS credentials on (`CorsConfig`, `setAllowCredentials(true)`):** the dev frontend (`localhost:5173`) and API (`localhost:8080`) are different origins. A browser sends a cookie on such a `fetch` only with `credentials: 'include'` (8.2f), and accepts the answer only if it says `Access-Control-Allow-Credentials: true` and echoes the exact origin. Turning it on is safe because the origins are exact (2.4d refuses `*` and patterns at startup; a browser would refuse `*` with credentials anyway) and cookie-authenticated writes need the CSRF header (8.2d). An unlisted origin's preflight is still a 403 without the grant.
- **Signout, `POST /auth/signout`:**
  - **Shape:** public (`permitAll`), no body, 204, and a `Set-Cookie` from `AuthCookie.clear()`: empty value, `Max-Age=0`, otherwise the same attributes as `issue`. A browser deletes the cookie that matches name, path and domain, so the path and the absence of `Domain` must be identical; the security attributes are kept too, since some browsers refuse to overwrite a `Secure` cookie with a non-secure one. `issue` and `clear` share one builder, so they can't drift.
  - **Why public and idempotent:** signing out must work with an expired or bad cookie, and with none (a second click), always answering 204 and clearing. With no credential there is nothing to protect.
  - **Forced signout is blocked:** a valid cookie without the CSRF header is a 403 like any cookie-authenticated write (8.2d), with no `Set-Cookie`, so another site cannot sign a user out.
  - **Not revoked:** the API is stateless (no session or token table), so signout removes only the browser's copy. A copied token stays valid until `exp` (24h). A deny-list would be a new table and a check on every request, which is new scope, not this row. Pinned by a test so it can't be mistaken for revocation.
  - **Bearer callers** get 204 too. The clearing header is harmless to a client without the cookie.
- **Role matrix:** the new endpoint has its row (`publicEndpoint("POST", "/auth/signout")`), as `everyMappedEndpointHasARowInTheMatrix` requires.
- **Tests (backend 2653 -> 2667; ai-service and frontend unchanged and passing):**
  - **`RealHttpSignoutTests`** (new, 8, real HTTP):
    - with a cookie and the header: 204, empty body, cleared;
    - the clearing cookie has the issued one's `Path`, `Secure`, `HttpOnly`, `SameSite` and no `Domain`;
    - no cookie: 204, cleared;
    - a bad cookie: 204, cleared;
    - bearer: 204;
    - a valid cookie without the header: 403 `CSRF_HEADER_MISSING`, nothing cleared;
    - the token still works after signout;
    - `GET` does not sign out.
  - **`RealHttpCorsTests`:** "credentials are never allowed" is replaced by credentials allowed for a listed origin, echoed and never `*`, on a request and on a write's preflight; an unlisted origin's preflight is 403 without the grant.
  - **The role-matrix row.**
  - **`CorsPropertiesTests`** (unit): "never allows credentials or a wildcard" is now "allows credentials only with exact origins and no wildcard". The first full run failed on it, as expected after the change.
- **Mutation-checked:**
  - clearing without `Max-Age=0`: 3 failures
  - a different path on the clearing cookie: 4
  - signout not public: 5
  - credentials off: 1

## 8.2f — The frontend client sends the cookie and the CSRF header (new row)

- **`credentials: 'include'` on every call.** The dev frontend (`localhost:5173`) and the API (`localhost:8080`) are different origins. Without it the browser neither sends the auth cookie nor stores the one in the signin answer (8.2b); the backend's CORS now allows credentials (8.2e). `same-origin` would work only if a later deployment (Phase 16) serves both from one origin, and `include` is right in both cases, so the client doesn't have to know which.
- **`X-Requested-With: XMLHttpRequest` on every non-GET call** (`CSRF_HEADER`, `CSRF_HEADER_VALUE`, exported for tests and later code). The backend accepts any non-blank value (8.2d); `XMLHttpRequest` is the conventional one, which some proxies and frameworks recognise. It is **set after the caller's headers**, so a caller can't remove or blank it by accident. **Not on GET:** a GET changes nothing and the backend doesn't ask for the header there, while sending it would turn every read into a preflighted CORS request (a non-safelisted header), one extra round trip each.
- **No token handling in the frontend at all**, which is the point of the cookie: there is nothing to store, refresh or attach. The `headers` option stays for other uses.
- **Tests (frontend 68 -> 78; backend and ai-service unchanged and passing):** `client.test.ts`:
  - the existing header assertions now include the CSRF header on the JSON `POST` and the `FormData` upload, and its absence on the plain `GET`;
  - each shorthand sends `credentials: 'include'` (5);
  - each write sends the header while a `GET` does not (4);
  - a caller's blank `X-Requested-With` does not replace it (1).
- **Mutation-checked:**
  - no `credentials`: 5 failures
  - header on GET too: 6
  - header never: 7
  - the caller's value wins: 1
- **Not verified in a real browser yet:** the cross-origin cookie round trip (signin sets it, `/auth/me` sends it) needs a page that signs in. 8.3b builds it and is the place to check it end to end in the browser pane, Safari's `Secure`-on-localhost caveat included (`docs/suggestions.md`, 8.2b).

## 8.2g — The Java runner image has no entrypoint (new row; the 7.7b suggestion, decided)

- **Why the row exists:** 7.7b (PR on 2026-10-28) made a container that never ran a `SANDBOX_ERROR`, but left one hole, filed in `docs/suggestions.md` because each fix was a choice no default covered. The `eclipse-temurin:17-jdk-jammy` base sets `ENTRYPOINT ["/__cacert_entrypoint.sh"]`, so on the Java image every command went through that script's shell. A compile or run command missing from the image (a config typo, a changed base image) became the shell's `exec: ...: not found`, exit 127 of a container that ran (`exited`, no `State.Error`), exactly like a program's own `exit(127)`, and was graded as the student's `COMPILE_ERROR` or `RUNTIME_ERROR`. Asked at 8.2f (it had sat in `current-progress.md` since then), the human chose **`ENTRYPOINT []` in the Java Dockerfile**, over `--entrypoint ""` in `DockerRunArguments` and over leaving it, to be done before 8.3b.
- **The change:** `ENTRYPOINT []` in `infra/sandbox/java/Dockerfile`, with the reason as a comment. Docker then runs the configured command directly; a missing one can't start (`created`, exit 127, `State.Error` set), which 7.7b already reports as `SANDBOX_ERROR`. **What the script did:** imports host CA certificates into the JVM's trust store when `USE_SYSTEM_CA_CERTS` is set. The sandbox has no network and never sets it, so nothing is lost (`javac` and `java` run as before; the Java suites pass). Resetting `ENTRYPOINT` also clears the base image's `CMD` (`jshell`), which the runner never used: it always passes the command.
- **Why the image, not a run flag:** it touches only the image that had the problem, and `DockerRunArguments` (the one place the restrictions live) stays as it is. The cost is that a future language's image could bring its own entrypoint back. Hence the guard: `SandboxImagesTests.noImageHasAnEntrypoint` inspects every configured image (`{{json .Config.Entrypoint}}` must be `null` or `[]`), so a new language fails the test, not the grading.
- **Tests (backend 2667 -> 2671; ai-service and frontend unchanged and passing):**
  - **`SandboxImagesTests`:** the new no-entrypoint guard (1).
  - **`DockerCliSandboxRunnerTests`:** the Java counterparts of 7.7b's Python cases. A compile command missing from the Java image is `SANDBOX_ERROR` with no exit code and nothing of Docker's text shown. A run command missing (the build with the real `javac` succeeds) is `SANDBOX_ERROR`. The control: a Java program's own `System.exit(127)` stays the student's `RUNTIME_ERROR`, exit 127. The comments that pointed at the Java gap are updated.
- **Mutation-checked:** with `ENTRYPOINT []` removed (the image rebuilt with the script), the guard and both Java missing-command tests fail (3). The failure text shows the old behaviour exactly: `/__cacert_entrypoint.sh: line 123: exec: javac-does-not-exist: not found`, graded as the student's error. With it back, all pass and the image's entrypoint is `null` again.

## 8.3b — Sign-in form; the cookie holds the JWT, `GET /auth/me` says who is signed in

- **Storage, as resolved at 8.3b (the human's decision):** the JWT is the backend's `httpOnly` cookie (8.2b–8.2f). The frontend stores nothing: no `localStorage`, no `sessionStorage`, no token in memory. The row's text still says "store the JWT"; its notes (updated at 8.2b) record that the cookie does the storing.
- **Auth state: React context, no library** (the first shared state; the frontend `CLAUDE.md` said the introducing task decides). `AuthProvider` asks `GET /auth/me` once on load. The browser attaches the cookie (`credentials: 'include'`, 8.2f), so 200 = `signedIn` with that user and anything else = `signedOut` (401, a deleted account, a server error, an unreadable answer, no network). **A server error counts as signed out, not as a third state:** the next action that needs a session will say so, and showing a sign-in form to someone whose session is fine is the lesser harm. `loading` lasts until the answer, which 8.3c's `ProtectedRoute` must wait for before redirecting.
- **Race between sign-in and the load check:** `signedIn(user)` abandons a `GET /auth/me` still in flight (abort plus a guard on both branches), so a late 401 from before the sign-in cannot sign the user out again. Under `StrictMode` the dev build mounts twice; the first check is aborted by its cleanup (the `ERR_ABORTED` requests seen in the browser are this).
- **Sign-out** is in the provider (`POST /auth/signout`, then `signedOut`; a failure is rethrown and the state kept, since the cookie may still be set). No button yet: 8.5a's header and menu place it.
- **Sign-in page:** email and password, both required here (only "not empty": signin has no length rules of its own beyond the backend's). On success it records `response.user` (no second `/auth/me`) and navigates to `/courses` with `replace`, so Back doesn't return to the form. **`INVALID_CREDENTIALS`** (wrong password and unknown email are one answer, 2.4a) is one form message, with the password cleared and the email kept. `VALIDATION_FAILED` goes to the fields (or the form, if none match); anything else is one alert. The button is disabled while sending. **Someone already signed in** sees "You are signed in as …" with a link to their courses; the form stays, so they can sign in as someone else. No redirect: route guards are 8.3c.
- **Shared form pieces, extracted as 8.3a planned:** `src/components/TextField.tsx` (generic over the form's field names, id prefix per form) and `fieldErrorsOf` in `src/components/fieldErrors.ts`. `SignUpPage` now uses both (its behaviour and tests unchanged), and its local `User` type is replaced by `auth/authContext`'s.
- **`App` now wraps the routes in `AuthProvider`**, so a test of `App` gets the whole app. Every such test answers the provider's `/auth/me` through `src/test/api.ts`'s `stubApi`, which also keeps each page test's request counts its own.
- **Checked in a real browser (Chromium, the desktop app's browser pane), against the dev profile backend and the Vite dev server** (8.2f left this for here):
  - Signed in with the seeded dev login: the CORS preflight was 200, `POST /auth/signin` 200, and the page went to `/courses`.
  - **After a full reload**, `GET /auth/me` was 200 and the sign-in page said "You are signed in as student1@dev.example.com". The cookie set by the cross-origin signin is kept and sent (`Secure` on `http://localhost`, `SameSite=Strict` between ports 5173 and 8080).
  - `document.cookie` was empty: script can't see the token.
  - From the page: `POST /auth/signout` without the CSRF header was 403 `CSRF_HEADER_MISSING`, and the user stayed signed in. With it: 204, and then `/auth/me` was 401.
  - **Local setup found on the way** (`docs/suggestions.md`): a host Postgres service already holds 5432, so the compose database ran on 5433 for the check. `backend/.env`'s `AI_SERVICE_TOKEN` is blank, so the backend won't start from it (a throwaway value was set in the run script only; no file was changed).
- **Tests (frontend 78 -> 102; backend and ai-service unchanged and passing):**
  - **`AuthProvider.test.tsx`** (11):
    - loading until `/auth/me` answers, then signed in;
    - the one request, with the cookie;
    - 401, a deleted account, 500, an unreadable answer and a network failure all mean signed out;
    - `useAuth` outside the provider throws;
    - `signedIn` records;
    - a sign-in beats a late 401;
    - sign-out posts with the CSRF header and signs out;
    - a failed sign-out rethrows and keeps the user.
  - **`SignInPage.test.tsx`** (12):
    - the fields;
    - the request (body, cookie, CSRF header);
    - success goes to the courses;
    - after a sign-in the state is known on another page without a second `/auth/me`;
    - already signed in is told so;
    - an empty form is refused without a request;
    - wrong credentials: one message, password cleared, email kept;
    - backend field errors, and the unknown-field fallback;
    - a network failure, then a retry;
    - the disabled button;
    - the link to sign up.
  - `App.test.tsx` and `SignUpPage.test.tsx` now use `stubApi`, with no other change.
- **Mutation-checked:**
  - a sign-in does not abandon the open check: 1 failure
  - an error leaves it loading: 6
  - signed out before the answer: 1
  - the page does not record the user: 1
  - the password kept after wrong credentials: 1
  - no navigation: 4

## 8.3c — `ProtectedRoute` and `RoleRoute`

- **Layout routes, not wrappers per element** (`src/auth/routeGuards.tsx`). `<Route element={<ProtectedRoute />}>` around the routes it guards, which render through `<Outlet />`. One guard covers a whole group of routes, and `App` stays a readable route table. Both files export only components (react-refresh lint), and `Role` and `returnPathOf` live in `authContext.ts`.
- **Loading is not signed out:** while `GET /auth/me` (8.3b) has not answered, both guards show "Checking your sign-in…" (`role="status"`) and never redirect. Redirecting then would send a signed-in user to the sign-in page on every reload.
- **Signed out → `/signin`, remembering where they were going:** `state.from` = path + query, set by `Navigate` with `replace`, so Back doesn't bounce off the guard. After a successful sign-in the page returns there instead of `/courses`. **`returnPathOf` accepts only a path of this app:** a string starting with one `/` (not `//host`, not `/\host`, which some browsers read as a host), and not the sign-in page itself. Anything else falls back to `/courses`. History state is set by our own code, but a crafted state must still never become an open redirect.
- **Signed in without the role → `/`:** the row says "redirects"; 8.4a makes `/` send a signed-in user to their role's home, so `/` is the one target that is right for every role and can never loop back into a guarded route. No "forbidden" page: the backend's 403 is what protects data, and the UI just doesn't show what the role can't use.
- **Roles are listed explicitly** (`roles={['TEACHER', 'ADMIN']}`): no role implies another, the same convention as the backend's `@PreAuthorize` (2.5a, no hierarchy). A test pins that a teacher is not let into an admin-only route.
- **What is guarded now:** `/courses` (`ProtectedRoute`). No route needs a role yet. The first will be 8.5b's admin page (`RoleRoute roles={['ADMIN']}`), so `RoleRoute` is tested on stand-in routes.
- **Sign-out on a guarded page** needs nothing extra: the state turns `signedOut` and the guard sends the user to `/signin`.
- **Convenience only** (the row's note): every guard decision comes from `/auth/me`'s answer, and the backend checks every request itself.
- **Not checked in a real browser this time:** the guards are pure routing on top of 8.3b's state, whose browser behaviour was checked at 8.3b. The router tests render the real `react-router` and the real provider.
- **Tests (frontend 102 -> 129; backend and ai-service unchanged and passing):**
  - **`routeGuards.test.tsx`** (24):
    - `ProtectedRoute`: waits while loading, then shows the page; signed in sees it; signed out goes to `/signin` with path and query remembered; signing out on the page leads to `/signin`;
    - `RoleRoute`: teacher and admin pass, a student goes to `/`, a teacher is refused an admin route, signed out goes to `/signin` remembering the path, it waits while loading;
    - `returnPathOf`: 4 app paths kept, 10 non-paths refused.
  - **`SignInPage.test.tsx`** (+2): through the real `App`, `/courses?page=3` signed out → sign-in → back at `/courses?page=3`; a crafted `//evil.example` return path ends at `/courses`.
  - **`App.test.tsx`**: `/courses` shows for a signed-in user and sends a signed-out one to sign in (replacing the plain `/courses` case).
- **Mutation-checked:**
  - `ProtectedRoute` redirects while loading: 4 failures
  - `RoleRoute` ignores the role: 2
  - the query not remembered: 2
  - `//` allowed as a return path: 2
  - sign-in ignores the return path: 1
  - `/courses` not guarded: 2
- **Also in this commit (docs only):** the two local-setup items filed at 8.3b are marked resolved in `docs/suggestions.md`. The human stopped the host's Postgres service and copied the AI service token into `backend/.env`, and the backend then started from `backend/.env` alone (checked: health UP, seed signin 200, the two tokens equal by hash).

## 8.4a — Landing page for guests; signed-in users go to their role's home

- **Role homes, from the row's notes, written once:** `homeOf(role)` in `src/auth/authContext.ts` (student `/courses`, teacher `/courses`, admin `/admin/users`). The teacher's "courses with management actions" is the same route as the student's; the course page grows those actions when it's built, so no separate teacher route is made now.
- **The admin's home needed a route:** `/admin/users` is added as a placeholder page (`AdminUsersPage`, "Users"), behind `RoleRoute roles={['ADMIN']}`, the same way 8.1b added placeholders. 8.5b fills it with the user list and role selector. The path mirrors the backend's `GET /admin/users`. Without it, the admin's redirect would have landed on the not-found page.
- **`/` decides by the auth state:**
  - `loading`: "Checking your sign-in…" (no flash of the guest page for a signed-in user);
  - `signedIn`: `Navigate` with `replace` to `homeOf(role)`;
  - `signedOut`: the landing page.

  This also completes 8.3c's wrong-role redirect: `RoleRoute` sends a user without the role to `/`, which now forwards to their own home (a student or teacher opening `/admin/users` ends at `/courses`). It can't loop, because each home is open to its own role.
- **Sign-in follows the same rule:** without a return path from a guard (8.3c), it now goes to `homeOf(user.role)`, not always `/courses`, so an admin lands on user management. Its "already signed in" link points to the same home ("Continue").
- **The landing text describes what the platform does as built or specified** (overview of `docs/project-specifications.md`):
  - automatic testing of Java and Python submissions in an isolated sandbox;
  - the rubric score;
  - public test results;
  - up to three hints that explain without giving the solution and never change the score;
  - for teachers: courses, assignments with public and hidden tests, a weighted rubric, review and manual marks.

  It doesn't advertise what isn't built yet (similarity checks, static analysis). The sign-up link says "create a student account", since signup always makes a student. No styling, as before.
- **Tests (frontend 129 -> 141; backend and ai-service unchanged and passing):**
  - **`HomePage.test.tsx`** (new, 11):
    - a guest sees the landing (heading, both sections, the score sentence) and can go on to sign in and to sign up;
    - student, teacher and admin are each sent to their home (location checked);
    - it waits while `/auth/me` is open;
    - `/admin/users` is open to an admin, a student and a teacher are sent through `/` to `/courses`, and a guest goes to sign in.
  - **`SignInPage.test.tsx`** (+1): an admin's sign-in lands on `/admin/users`.
  - **`App.test.tsx`**: the guest-page cases now wait for the auth check (`findByRole`).
- **Mutation-checked:**
  - signed-in users see the guest page: 5 failures
  - the admin's home is the courses: 2
  - no wait while loading: 1
  - sign-in ignores the role: 1
  - the admin page open to any signed-in user: 2

## 8.5a — Header and nav shell, role-aware menu, sign-in state, sign-out

- **A layout route around every route** (`AppShell`: `Header`, then `<Outlet />`), so the header is on every page, the not-found page included, without each page including it.
- **The menu is a table per role** (`MENU` in `Header.tsx`, the one place it is written):
  - student: Courses; teacher: Courses (the teacher's management actions will live on the course pages, 8.4a);
  - admin: Users (their home, 8.4a) and Courses (the backend's `GET /courses` gives an admin every course, 3.1b);
  - a guest: Sign in and Sign up;
  - **while `/auth/me` is open, no menu and no button**, so the wrong menu never flashes before the role is known.

  Links are `NavLink`s, which mark the current page with `aria-current="page"`. A role is offered only the links it can use; the backend still decides every access.
- **Sign-in state:** "Signed in as {full name} ({Role})" and a Sign out button. The button is disabled while the request runs. A failure shows "Could not sign out. Please try again." and keeps the user signed in (the provider rethrows and keeps the state, 8.3b).
- **Sign-out does not navigate; the route guards do.** The first version jumped to `/` after signing out. The tests showed it racing the guard: the state turned signed out, `ProtectedRoute` on `/courses` redirected to `/signin`, and that redirect landed after the jump to `/`. Navigating before the state changes would instead send a still-signed-in user from `/` to their home. So there is one rule, owned by the guards:
  - on a guarded page, the guard sends the user to `/signin` and remembers the page (8.3c), so signing in again returns there;
  - on a public page they stay, now as a guest.
- **No `<h1>` in the header:** the app's name is a link to `/`, so each page keeps exactly one `<h1>` (the 8.1b convention, which the App tests check). The header's "Sign in" and "Sign up" links share names with links inside pages, so four existing tests now look inside `main`.
- **Checked in a real browser** (the dev-profile backend from `backend/.env` unchanged, the compose database on 5432, Vite):
  - the guest landing showed Grading platform, Sign in, Sign up and a single `<h1>`;
  - signed in as the seeded admin, a full reload of `/` went to `/admin/users`, with Users (`aria-current="page"`) and Courses and "Signed in as Ada Admin (Admin)";
  - clicking Sign out landed on `/signin` with the guest menu, and `/auth/me` was 401 afterwards.

  The pane was hidden (0×0 viewport), so the sign-in and the click were done from page script, not by pointer.
- **No styling**, as before; the shell is plain semantic HTML (`header`, `nav aria-label="Main"`).
- **Tests (frontend 141 -> 154; backend and ai-service unchanged and passing):** `Header.test.tsx` (new, 13):
  - the guest menu and no sign-out;
  - each role's menu (student, teacher, admin);
  - who is signed in, with the role;
  - nothing while loading;
  - the current page marked;
  - a menu link goes to its page;
  - on the not-found page, with its name linking home and one `<h1>`;
  - sign-out: posts with the CSRF header, then the guard's `/signin` and the guest menu; a failure says so and keeps the user; the disabled button sends once; on a public page the user stays.

  Plus the four link lookups scoped to `main`.
- **Mutation-checked:**
  - the admin menu without Users: 2 failures
  - the guest menu while loading: 1
  - no header in the shell: 13
  - the button never disabled: 1
  - a failure not shown: 1

## 8.5b — Admin page: the user list with a role selector

- **The admin's home, `/admin/users`** (placeholder since 8.4a), is now the list from `GET /admin/users` (2.5c: `PageResponse` of `{id, email, role}`, oldest account first, 20 per page by default). It is a table ("All accounts, oldest first": Email, Role). The admin's own row says "(you)". The row has only what the API gives (no name): enough to find whom to promote, as 2.5c decided.
- **Pages:** the page is in the URL, 1-based for people (`?page=2` asks the API for `page=1`). Anything that isn't a whole number from 1 (`abc`, `0`, `-2`, `1.5`) is the first page, and page 1 has no parameter. Previous and Next are disabled at the ends, with "Page X of Y (N users)". A page past the end is the API's empty page: "No users on this page."
- **Role selector per row**, wired to `PATCH /admin/users/{id}/role` (2.5b) on change, labelled "Role of {email}":
  - **while a change is saved, every selector is disabled**, one change at a time, so two can't cross;
  - on success the row shows the role the answer returned, and a status says "{email} is now {Role}.";
  - a refusal (409 `LAST_ADMIN`, 403 for a caller no longer admin in the database (7.7e), 404 for a vanished user, anything else) shows `ApiError.message`, and the selector stays on the stored role, since the row is only updated from an answer.

  No confirmation step: one change is one request, and it can be changed back the same way.
- **An admin who demotes themselves leaves the page at once.** On success for their own row, the page updates the auth state (`signedIn` with the new role), so `RoleRoute` sends them to `/`, which sends them to their new home. The backend already treats the stored role as theirs for role changes (7.7e), while their token still says `ADMIN` until it expires; following the stored role in the UI avoids leaving them on a page whose next action would be refused.
- **Loading without `setState` in the effect body** (react-hooks lint): the answer is stored with the page it is for, and the page is "loading" whenever that isn't the URL's page. The effect only starts the request (with an `AbortController`, so a page change cancels the old one), and its callbacks set the state.
- **`PageResponse<T>`** is typed once in `src/api/types.ts`, for the later lists.
- **Checked in a real browser** against the dev-profile backend and the seeded users:
  - the page listed all four accounts with their roles, "(you)" on the admin, and "Page 1 of 1 (4 users)";
  - `student2` was changed to Teacher and back to Student through the selector, each shown on the page and confirmed stored via `GET /admin/users`;
  - demoting the only admin showed "The last remaining admin cannot be demoted", with the selector still on Admin;
  - the seeded roles ended exactly as they started, and the browser was signed out after.
- **Tests (frontend 154 -> 168; backend and ai-service unchanged and passing):** `AdminUsersPage.test.tsx` (14):
  - the list: rows, roles, "(you)", the counts, page 0 asked with the cookie;
  - loading, a failed load, an empty page;
  - next and previous with the URL kept and the ends disabled;
  - the URL-to-API page mapping (5 cases);
  - a role change: the request, the CSRF header, the row, the message;
  - every selector disabled while saving;
  - a refusal shown with the role unchanged;
  - self-demotion lands on `/courses`.
- **Mutation-checked:**
  - the 1-based page sent to the API: 7 failures
  - self-demotion not followed: 1
  - the row not updated after a change: 1
  - the selectors not disabled while saving: 1
  - a fractional page accepted: 1
  - Next never disabled: 1

## 8.6a — RTL tests: sign-up form validation and submit

- **Most of the row was already done:** 8.3a wrote the sign-up form's tests with React Testing Library together with the form (21 tests in `SignUpPage.test.tsx`, through the real `App` with `fetch` stubbed). They already cover:
  - the fields and the absence of a role;
  - the request and the confirmation;
  - every client check: empty, only spaces, password 7/8 characters, 72/73 bytes and 36/37 `é`, a 256-character name;
  - the backend's field errors, the unknown-field fallback and `EMAIL_ALREADY_USED`;
  - a 500, an unreadable answer, a network failure and a retry;
  - the disabled button, errors cleared on a new submit, and both links to sign-in.

  This row didn't rewrite them. It **audited them against the page's own checks and filled the gaps**, the way 8.1a confirmed rather than redid.
- **The gaps found and now tested (5 tests):**
  1. **The email limit** (255 characters, measured after trimming) had no test at all: a 256-character email is refused here, without a request.
  2. **The exact boundaries**, so an off-by-one would fail: a name of exactly 255 characters is sent, and a 255-character email with spaces around it is accepted.
  3. **Submitting with Enter** rather than the button: Enter in a field submits the form, and on an incomplete form it shows the field errors without a request.
- **A fact the tests surfaced:** a `type="email"` input strips surrounding spaces itself (the HTML value sanitization, which jsdom applies like a browser), so the email reaches the request already trimmed. The test asserts that (it first assumed "sent as typed"), and the backend still trims and lower-cases again (8.0d).
- **No change to `SignUpPage`**: the code already did all of this, but four of its rules had no test that would notice if they broke.
- **Tests (frontend 168 -> 173; backend and ai-service unchanged and passing).**
- **Mutation-checked** (each new test fails for its own rule):
  - no email length check: 1 failure
  - the name limit off by one: 1
  - the email limit off by one: 1
  - no submission on Enter (the button a plain click handler, so clicking still works): 2

  An earlier "no form submit handler" mutation failed 24 tests, so it didn't isolate Enter; the plain-button version does.

## 8.6b — RTL tests: a protected route redirects when logged out

- **Already covered by 8.3c, on stand-in routes** (`auth/routeGuards.test.tsx`):
  - the guard waits while `/auth/me` is open;
  - a signed-out user goes to `/signin` with path and query remembered;
  - signing out on a guarded page leads there.

  Plus `App.test.tsx`'s `/courses` case, the return after sign-in (`SignInPage.test.tsx`) and the header's sign-out (`Header.test.tsx`). As at 8.6a, this row **audits and fills the gaps** rather than rewriting.
- **What was missing, now in `src/protectedRoutes.test.tsx`, through the real `App`** (its real routes, guards and sign-in page, not stand-ins):
  1. **Every real guarded route**, `/courses` and `/admin/users`, with and without a query, sends a guest to the real sign-in page (its form is there) with the path remembered. A new guarded route belongs in this table.
  2. **The guarded page's own data is never requested** for a guest: only the provider's `/auth/me` goes out, no `GET /admin/users`.
  3. **The redirect replaces the history entry:** from `/` then `/courses`, Back after the redirect returns to `/`, instead of bouncing off the guard again.
  4. **Every way of being signed out redirects:** a 401, an account deleted since the cookie was set (`ACCOUNT_NO_LONGER_EXISTS`), a server error on the check, and no network.
  5. **While the check is open** the real route shows "Checking your sign-in…": no redirect, no page, no data request.
- **No production change.**
- **Tests (frontend 173 -> 184; backend and ai-service unchanged and passing).**
- **Mutation-checked:**
  - the redirect pushing instead of replacing: 1 failure
  - the admin page not guarded: 4
  - `RoleRoute` rendering the page while loading: 2
  - the query not remembered: 2

## 8.6c — RTL tests: a role route blocks a student from teacher and admin pages (last Phase 8 row)

- **Already covered before this row:**
  - 8.3c (`auth/routeGuards.test.tsx`, stand-in routes): a student refused a teacher-and-admin route, a teacher refused an admin-only route, the wait while loading;
  - 8.4a (`HomePage.test.tsx`): a student and a teacher opening `/admin/users` end at `/courses`;
  - 8.5a (`Header.test.tsx`): a student's menu offers no admin link.

  As at 8.6a and 8.6b, this row **audits and fills the gaps**.
- **What was missing, now in `src/roleRoutes.test.tsx`, through the real `App`:**
  1. **A table of every real role-restricted route** (`ROLE_ROUTES`: today `/admin/users`, with and without a query), checked for **every role**. An allowed role gets the page. Every other role is sent to **their own** home (`homeOf`) and **the page's data is never requested**. No teacher-only route exists yet (Phase 9 adds the teacher pages); each one joins the table as it comes, which makes every role get checked against it. The stand-in teacher route of 8.3c covers the guard until then.
  2. **In-app navigation, not only a fresh load:** a student already on `/courses` who navigates to `/admin/users` inside the app is sent back to `/courses`, with no request.
  3. **No flash:** the admin page never mounts for a student (no heading, no list request), because the guard decides before the page renders.
- **No production change.**
- **Phase 8 is complete** with this row. Phase 9 opens with four GATE rows (9.0a–9.0d) from the phases 0-7 review, each needing the human's decision.
- **Tests (frontend 184 -> 192; backend and ai-service unchanged and passing).**
- **Mutation-checked:**
  - a teacher let into the admin page: 2 failures
  - `RoleRoute` ignores the role: 6
  - a wrong role sent to sign-in instead of home: 6
