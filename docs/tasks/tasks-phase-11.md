# tasks-phase-11.md — Real-Time Updates (WebSockets)

| Commit | Task | Status | Notes |
| --- | --- | --- | --- |
| 11.1a | Add Spring WebSocket (STOMP) support to `/backend` | Not started | New dependency — gate applies. |
| 11.1b | Authenticate the WebSocket handshake/connection with the JWT | Not started | Reuse the Phase 2 token validation; an unauthenticated connection is refused, not just unsubscribed. |
| 11.2a | Publish submission status changes (`QUEUED`, `RUNNING`, `GRADED`, `ERROR`) from the worker to `/topic/submissions/{id}` | Not started | Hook into the single place that transitions status (5.2b) — don't scatter publish calls. Publish after the DB commit, not before, so a client that reacts by re-fetching sees the new state. |
| 11.2b | Authorize subscriptions: only the submission's owner and the course's teacher/admin may subscribe | Not started | A subscribe-authorization hole leaks other students' results; this is the row to test hardest. |
| 11.2c | Publish a "manual grade saved" event on the submission topic | Not started | Lets an open result page refresh the provisional total when a teacher saves a `MANUAL` score (5.4e). Hints need no event: the hint response already carries the hint (7.2a). |
| 11.3a | Frontend hook subscribing to the submission topic; result page updates live | Not started | Replaces 9.5b's polling as the primary mechanism. |
| 11.3b | Fall back to polling when the socket drops, reconnect with backoff | Not started | A student at the deadline on bad Wi-Fi is the real use case. |
| 11.4a | Test: handshake without a token is refused | Not started |  |
| 11.4b | Test: events arrive in order for one submission | Not started |  |
| 11.4c | Test: subscribing to another student's submission is rejected | Not started |  |
