# tasks-phase-13.md — Static Analysis & Code Quality

| Commit | Task | Status | Notes |
| --- | --- | --- | --- |
| 13.1a | AI service `POST /analyze`: cyclomatic complexity, function length, nesting depth per function, for Java and Python | Not started | **GATE**: a metrics library such as `lizard` (supports both languages) is a new dependency. Analysis only parses code — it never executes it, which keeps the sandbox as the only place student code runs. |
| 13.1b | Style/lint violations for Python | Not started | **GATE**: e.g. `ruff` — new dependency. Note `ruff` is already planned as the AI service's own linter (0.5b), but using it on student code is a separate use; confirm with the human. |
| 13.1c | Style/lint violations for Java | Not started | **GATE**: no default. Options: Checkstyle or PMD (need a JVM next to the AI service, or run in a sandbox image), or a small built-in rule set. Resolve before starting. |
| 13.2a | Response model: per-function metrics, violations (rule, line, message), summary | Not started | Backend DTO mirrors it, as with 6.3a. |
| 13.2b | Code-smell rules (long method, deep nesting, too many parameters) with named-constant thresholds | Not started | Keep the rule set small and documented — each rule must be explainable in the thesis. |
| 13.3a | Migration: `static_analysis_results` table (submission\_id, metrics JSONB, violations JSONB, analyzed\_at) | Not started |  |
| 13.3b | After `GRADED`, call `/analyze` on the background worker and persist the result | Not started | Failure here must not fail grading — record "analysis unavailable" and continue. |
| 13.4a | Enable the `STATIC_ANALYSIS` rubric criterion type (lifting the 3.4a restriction) with `config` = score thresholds | Not started | Config maps metrics to a 0–1 score, e.g. complexity above N loses points. Validate the config shape on save. The suggested default weight is a small named constant (`10` of 100, per CLAUDE.md) — the teacher can change it; nothing forces it. No source found showing quality metrics should weigh heavily in a grade, so keep the default modest and the choice the teacher's. |
| 13.4b | Extend the scoring service (5.4a) to score `STATIC_ANALYSIS` criteria | Not started | Stays a pure function. Re-score existing graded submissions when a criterion is enabled? Decide and record it in decisions — don't leave it implicit. |
| 13.5a | Include the analysis findings in the hint prompt context (6.3b) | Not started | Lets hints address quality, not only correctness. Level rules and the leak guard still apply. |
| 13.5b | Relax 7.2c: allow quality hints when all tests pass but violations exist | Not started |  |
| 13.6a | Result page "Code quality" tab: metrics, violations list with line numbers | Not started |  |
| 13.6b | Teacher rubric editor: threshold configuration for the static-analysis criterion | Not started | Pre-fill the weight field with the suggested default from 13.4a but leave it editable; show the live "weights sum to 100" indicator from 9.2c. |
| 13.7a | Tests with fixture code of known complexity (hand-counted decision points) | Not started | Count branches by hand for the expected value. |
| 13.7b | Test: score mapping at, below, and above each threshold; analysis outage does not fail grading | Not started |  |
