# Decisions / Assumptions — Phase 6 (`docs/tasks/tasks-phase-06.md`)

Historical, append-only. One entry per commit where a non-obvious call was made — a gap in the task spec, a gate resolution, a formatter/tool choice, a bug found and fixed. Not read every session — see the note in root `CLAUDE.md`. Load this only when auditing a specific Phase 6 decision.

## 6.1a — FastAPI app skeleton, `GET /health`

- **Already delivered by 0.1c, closed without code (gate, the human's decision).** `ai-service/src/ai_service/main.py` holds the FastAPI `app` with `GET /health` answering `{"status": "ok"}`, and `tests/test_health.py` checks it. The row's wording conflicted with the repo state, which the root `CLAUDE.md` makes a gate. The options offered were: mark it Done with no code (chosen), tighten the skeleton (an app factory for the 6.1b/6.1c tests), or change the answer to the backend's `{"status": "UP"}`.
- **Left as it is, for the tasks that own it:**
  - `/health` keeps `{"status": "ok"}`; the backend's says `UP`. If the hosting health checks (16.8a) want one shape, that task decides.
  - No app factory yet. If 6.1b's settings or 6.1c's token need the app built per test, those commits add it.
- **Tests:** none added or changed. All three projects were run and pass (backend 2226, ai-service 1, frontend 3; lint and format clean).
