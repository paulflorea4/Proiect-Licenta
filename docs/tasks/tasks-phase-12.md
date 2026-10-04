# tasks-phase-12.md — Plagiarism & Similarity Detection

| Commit | Task | Status | Notes |
| --- | --- | --- | --- |
| 12.1a | Tokenizer in the AI service: source → normalized token stream (comments and whitespace removed, identifiers → `ID`, literals → `LIT`) for Python | Not started | Python's standard `tokenize`/`ast` is enough — no new dependency. Normalization is what makes renamed-variable copies match. |
| 12.1b | Tokenizer for Java | Not started | **GATE**: needs a Java-capable lexer/parser (e.g. Pygments for tokens, tree-sitter for a real AST) — new dependency, pick with the human. Token-based is the default; an AST variant is an optional thesis extension. |
| 12.1c | Remove tokens that come from the assignment's `starter_code` before comparing | Not started | Otherwise every pair of students looks similar because they share the boilerplate. |
| 12.2a | k-gram hashing over the token stream (rolling hash), `K` as a named constant | Not started | Pure functions throughout this phase — easiest to test against known examples. |
| 12.2b | Winnowing: select fingerprints from windows of size `W` (named constant), keeping their positions | Not started | Rightmost-minimum tie-breaking as in the original winnowing paper. |
| 12.2c | Test against the worked example from the winnowing paper (Schleimer, Wilkerson, Aiken) and the "any shared run of at least `W+K-1` tokens is detected" guarantee | Not started | Hand-verify the expected fingerprints; this is the test the thesis cites for correctness. |
| 12.3a | Similarity score between two fingerprint sets (e.g. containment/Jaccard) + matched token ranges mapped back to source line ranges | Not started | Document which measure was chosen and why in `docs/decisions/decisions-phase-12.md`. |
| 12.3b | `POST /similarity`: one submission plus a list of peer submissions → ranked matches with score and matched line ranges | Not started | Stateless, per CLAUDE.md: the backend sends the code; the service stores nothing. |
| 12.4a | Migration: `similarity_matches` table (submission\_a, submission\_b, score, matched ranges) | Not started | Store each pair once with a consistent ordering (lower id first) to avoid duplicate/mirrored rows. |
| 12.4b | After a submission is `GRADED`, compare it with the other students' best submissions for the same assignment | Not started | Never compare a student with themselves. Runs on the background worker, not the request thread. Cap the number of peers per call to bound payload size. |
| 12.4c | Persist matches at or above the threshold (named constant `0.70`, placeholder) | Not started | Don't tune the threshold yourself — the human's own experiment does that. |
| 12.5a | `GET /assignments/{id}/similarity` (teacher): flagged pairs sorted by score | Not started | Teacher/admin of the course only; students never see similarity data. |
| 12.5b | `GET /similarity/{id}`: both sources with matched ranges for side-by-side review | Not started |  |
| 12.6a | Teacher page: flagged pairs table | Not started | Wording must say "similar", not "plagiarized" — the tool flags, the teacher judges. |
| 12.6b | Side-by-side viewer with highlighted matched regions | Not started |  |
| 12.7a | Fixture tests: identical, renamed-variables, reordered functions, and independent solutions to the same problem — assert the score ordering | Not started | The ordering (identical > renamed > reordered > independent) is the meaningful assertion, not any absolute number. |
| 12.7b | Test: starter code alone does not produce a match; a student is never compared with themselves | Not started |  |
