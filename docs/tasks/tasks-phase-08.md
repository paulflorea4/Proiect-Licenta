# tasks-phase-08.md — React: Skeleton & Auth Flow

| Commit | Task | Status | Notes |
| --- | --- | --- | --- |
| 8.1a | Confirm the `/frontend` React+TS app (0.1d) is still current before adding to it | Not started | If 0.1d already scaffolded this, don't re-run `npm create vite`. |
| 8.1b | `react-router` set up with placeholder routes (`/`, `/signin`, `/signup`, `/courses`) | Not started |  |
| 8.2a | Fetch/axios wrapper reading API base URL from env, typed response helpers, consistent error handling for the backend's error shape (2.6a) | Not started | Reads the var from `frontend/.env.example` (0.3c). Every later phase's frontend work goes through this, not raw `fetch`. |
| 8.3a | Sign-up form wired to `POST /auth/signup` | Not started | No role selector — signup always creates a student. |
| 8.3b | Sign-in form wired to `POST /auth/signin`, store the JWT | Not started | Default: httpOnly cookie if Spring Boot sets one; otherwise document the fallback storage explicitly — don't silently pick `localStorage`. |
| 8.3c | `ProtectedRoute` (redirects unauthenticated users to `/signin`) and `RoleRoute` (redirects users without the required role) | Not started | Frontend role checks are a convenience only — the backend (2.5a) is what actually enforces access. |
| 8.4a | Landing page for guests describing the platform, redirecting signed-in users to their role's home | Not started | Student home = their courses, teacher home = their courses with management actions, admin home = user management. |
| 8.5a | Header + nav shell, role-aware menu, shows sign-in state, sign-out action | Not started |  |
| 8.5b | Admin page: user list with a role selector wired to 2.5b and 2.5c | Not started | Smallest useful admin UI; without it teachers can only be created through the API. |
| 8.6a | RTL test: sign-up form validation and submit | Not started |  |
| 8.6b | RTL test: protected route redirects when logged out | Not started |  |
| 8.6c | RTL test: role route blocks a student from teacher/admin pages | Not started |  |
