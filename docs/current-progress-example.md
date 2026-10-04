# Current Progress — Example

Format (four lines, copy exactly into `current-progress.md`):

- Phase: `<phase>` — `<path to tasks-phase-XX.md>`
- Next commit: `<commit id>` — `<short task name>`
- In progress: `<commit id>` — `<what's done, what's left>`, or `—` if nothing is mid-flight
- Blocked: `<exact decision, missing input, or PR awaiting review>`, or `—`

---

Examples:

**Mid-task:**

- Phase: Phase 6 — `docs/tasks/tasks-phase-06.md`
- Next commit: 6.3b — Prompt builder for the three hint levels
- In progress: 6.3b — Pydantic request/response models (6.3a) are done. Prompt builder returns the level-1 prompt. Still need the level-2 and level-3 templates and a test that the hidden-test section only ever contains pass/fail before this counts as finished.
- Blocked: —

**Blocked on a human decision:**

- Phase: Phase 16 — `docs/tasks/tasks-phase-16.md`
- Next commit: 16.6a — GATE: hosting choice + credentials
- In progress: —
- Blocked: 16.6a — needs your hosting platform choice (Fly.io / Render / VPS) and its credentials before any deploy-specific commit can start.

**Waiting on PR review** (this is a `Blocked:` case too — no separate line needed):

- Phase: Phase 4 — `docs/tasks/tasks-phase-04.md`
- Next commit: 4.3c — Output size cap for stdout/stderr
- In progress: —
- Blocked: PR #14 (commit 4.3b) awaiting review — checked per PR conventions' backoff schedule, no response yet.
