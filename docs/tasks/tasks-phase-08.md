# tasks-phase-08.md — React: Skeleton & Auth Flow

| Commit | Task | Status | Notes |
| --- | --- | --- | --- |
| 8.0a | Numeric values for enum fields are a 400 (`FAIL_ON_NUMBERS_FOR_ENUMS`) | Done | From the phases 0-7 review (`docs/suggestions.md`, low, auth), approved by the human. 2.5b says a numeric role is refused; Jackson 3 accepts ordinals by default (not yet run). Test `{"role":1}`; also `CriterionType`, `TestVisibility`. |
| 8.0b | A request from an origin not on the CORS list gets the standard JSON error body (`ACCESS_DENIED`), not plain text | Done | From the phases 0-7 review (`docs/suggestions.md`, low, auth), approved by the human. |
| 8.0c | A multipart body over 1 MB outside the submission upload is 413 `REQUEST_TOO_LARGE`, not `SOURCE_TOO_LARGE` | Done | From the phases 0-7 review (`docs/suggestions.md`, low, auth), approved by the human. |
| 8.0d | An email longer than 255 characters after lower-casing is a 400, not a 500 | Done | From the phases 0-7 review (`docs/suggestions.md`, low, auth), approved by the human. |
| 8.0e | **GATE** (the maximum) — a deadline beyond what the database stores is a 400, not a 500 | Done | From the phases 0-7 review (`docs/suggestions.md`, low, courses-assignments), approved by the human. |
| 8.0f | Deadlines are truncated to microseconds when received, so an echoed deadline is never a change | Done | From the phases 0-7 review (`docs/suggestions.md`, low, courses-assignments), approved by the human. |
| 8.0g | An auto-appended test position never exceeds `TestCaseRequest.POSITION_MAX` | Done | From the phases 0-7 review (`docs/suggestions.md`, low, courses-assignments), approved by the human. |
| 8.0h | A model hint containing NUL is a bad answer (clean retry, then 503), never a 500 at insert | Done | From the phases 0-7 review (`docs/suggestions.md`, low, hints and AI (two audits)), approved by the human. |
| 8.0i | The AI service treats a deeply nested model answer (`RecursionError`) and an odd `promptFeedback` as `LlmBadResponse` | Done | From the phases 0-7 review (`docs/suggestions.md`, low, AI service), approved by the human. Add a row to `MALFORMED`. |
| 8.0j | Real-HTTP test of the container error path (`ApiErrorController`), e.g. `GET /auth/me;x`, asserting the JSON shape | Done | From the phases 0-7 review (`docs/suggestions.md`, low, auth), approved by the human. |
| 8.0k | Token verification pins HS256 and requires `exp`; the different-algorithm test uses the same key | Done | From the phases 0-7 review (`docs/suggestions.md`, low, auth), approved by the human. |
| 8.1a | Confirm the `/frontend` React+TS app (0.1d) is still current before adding to it | Done | If 0.1d already scaffolded this, don't re-run `npm create vite`. |
| 8.1b | `react-router` set up with placeholder routes (`/`, `/signin`, `/signup`, `/courses`) | Done |  |
| 8.2a | Fetch/axios wrapper reading API base URL from env, typed response helpers, consistent error handling for the backend's error shape (2.6a) | Done | Reads the var from `frontend/.env.example` (0.3c). Every later phase's frontend work goes through this, not raw `fetch`. |
| 8.3a | Sign-up form wired to `POST /auth/signup` | Done | No role selector — signup always creates a student. |
| 8.2b | `POST /auth/signin` sets the JWT in an `httpOnly` cookie (`Secure`, `SameSite=Strict`, `Path=/`, `Max-Age` = the token's lifetime) and no longer returns the token in the body | Done | New row, the human's decision at 8.3b (JWT storage: `httpOnly` cookie, reversing 2.4a's "JSON body only"). The body keeps `expiresIn` and `user`. |
| 8.2c | `JwtAuthenticationFilter` also reads the token from the cookie; `Authorization: Bearer` stays accepted and wins when both are sent | Done | New row, the human's decision at 8.3b. |
| 8.2d | CSRF protection for cookie-authenticated requests: a state-changing request (`POST`/`PUT`/`PATCH`/`DELETE`) authenticated by the cookie must carry a custom header (e.g. `X-Requested-With`), which a foreign origin cannot send past the CORS preflight | Done | New row, the human's decision at 8.3b (custom header chosen over Spring's CSRF token, which needs script to read a token cookie across origins). `SameSite=Strict` stacks on top. |
| 8.2e | CORS allows credentials (`allowCredentials(true)`); public `POST /auth/signout` clears the cookie | Done | New row, the human's decision at 8.3b. The token is not revoked server-side (stateless): a copied token stays valid until it expires. |
| 8.2f | The frontend API client sends `credentials: 'include'` and the 8.2d header on state-changing requests | Done | New row, the human's decision at 8.3b. |
| 8.2g | The Java runner image has no entrypoint (`ENTRYPOINT []`), so a compile or run command missing from it is a `SANDBOX_ERROR`, never the student's `COMPILE_ERROR`/`RUNTIME_ERROR` | Done | New row, the human's decision at 8.2f on the open 7.7b suggestion (`docs/suggestions.md`): `ENTRYPOINT []` chosen over `--entrypoint` in `DockerRunArguments` and over leaving it; done before 8.3b. |
| 8.3b | Sign-in form wired to `POST /auth/signin`, store the JWT | Done | Storage resolved by the human: an `httpOnly` cookie set by the backend (8.2b-8.2f), so no token is kept in script. The app learns who is signed in from `GET /auth/me` on load. Was: "Default: httpOnly cookie if Spring Boot sets one; otherwise document the fallback storage explicitly — don't silently pick `localStorage`." |
| 8.3c | `ProtectedRoute` (redirects unauthenticated users to `/signin`) and `RoleRoute` (redirects users without the required role) | Not started | Frontend role checks are a convenience only — the backend (2.5a) is what actually enforces access. |
| 8.4a | Landing page for guests describing the platform, redirecting signed-in users to their role's home | Not started | Student home = their courses, teacher home = their courses with management actions, admin home = user management. |
| 8.5a | Header + nav shell, role-aware menu, shows sign-in state, sign-out action | Not started |  |
| 8.5b | Admin page: user list with a role selector wired to 2.5b and 2.5c | Not started | Smallest useful admin UI; without it teachers can only be created through the API. |
| 8.6a | RTL test: sign-up form validation and submit | Not started |  |
| 8.6b | RTL test: protected route redirects when logged out | Not started |  |
| 8.6c | RTL test: role route blocks a student from teacher/admin pages | Not started |  |
