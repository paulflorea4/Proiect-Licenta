# Suggestions

Historical, append-only. Things that would meaningfully help (a missing MCP connector, a slow test step, a repeated manual chore) noticed while working — comments, not tasks. Not read every session — see the note in root `CLAUDE.md`. Nothing here is installed, configured, or restructured without a task or explicit approval.


- (0.1c) `fastapi.testclient.TestClient` currently emits a `StarletteDeprecationWarning` with `httpx` ("install `httpx2` instead"). Harmless today; switching the dev dependency to `httpx2` would remove it, but that is a new dependency, so it was not done.
- (0.1c) The dev machine only has Python 3.14 while the project targets 3.12. Installing 3.12 (`uv python install 3.12`) would let local runs match CI — not done, it is external software.
