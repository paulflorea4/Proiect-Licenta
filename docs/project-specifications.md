# Grading Platform — Project Specification

## Overview

Grading Platform is a web application that grades programming assignments automatically and gives students AI-generated feedback. It solves a real gap: grading code by hand does not scale. Teachers spend hours running submissions, students wait days for feedback, and the feedback is often just a number. Open-source tools such as Moodle's VPL, CodeRunner and Autolab offer limited feedback beyond test results and similarity reports, while commercial tools such as CodeGrade, Codio and Gradescope combine tests, rubrics and plagiarism checks (and, in some cases, AI assistants) behind per-student pricing.

The core value proposition: a teacher defines an assignment once (description, tests, rubric); a student submits code and within seconds sees which tests passed, a rubric-weighted score, and hints that explain why a submission fails and how to improve — without the hints giving away the solution. The score is always deterministic (tests); the AI explains, it never grades the code. Initial languages are Java and Python, with the architecture designed so that adding a language is a configuration change.

## Architecture

The system is split into three independent services. Only the backend talks to the PostgreSQL database.

**Main API — Java Spring Boot** Handles everything user-facing and everything that must be trusted: authentication and role-based access, courses, assignments, submissions, sandboxed execution of submitted code, rubric scoring, the teacher dashboard, and real-time status updates. Exposes a REST API (plus a WebSocket endpoint) consumed by the React frontend. Spring Boot is the right choice here because it is mature and has excellent auth libraries (Spring Security + JWT). Sandboxed execution lives here, not in the AI service, so the component that runs untrusted code is the same one that owns the data and the scoring.

**AI Service — Python FastAPI** A stateless service called by the backend. Generates hints, proposes test cases, grades open answers, runs plagiarism similarity (winnowing) and static analysis. Python is the correct choice for this service because the AI/ML and code-analysis ecosystem (LLM clients, tokenizers, code-metrics and linting libraries) lives in Python. It never connects to the database and never executes student code. It is reachable only by the backend: every endpoint except the health check requires a shared-secret bearer token, and the service is never exposed to the public internet — otherwise anyone who found it could spend the LLM quota.

**Frontend — React + TypeScript** Single-page application. Talks only to the Spring Boot API. No direct communication with the Python service.

**Database — PostgreSQL** One database, owned by the backend. Schema migrations run through Flyway on backend startup.

**External services used:**

- Gemini API (free tier) — hint generation, test proposals, open-answer grading. Model name configured via `GEMINI_MODEL`, behind a provider interface so another LLM can be swapped in.
- Docker Engine — runs one isolated container per submission run.

## Features

**Guest (unauthenticated) access**

- See a landing page describing the platform
- Cannot view courses, assignments, or submissions
- Prompted to sign in on any restricted action

**Authentication and roles**

- Sign up / sign in with email + password
- JWT-based sessions managed by Spring Boot
- Three roles: `STUDENT`, `TEACHER`, `ADMIN`. Signup always creates a student; an admin promotes users to teacher
- No OAuth for now (adds scope; can be added later)

**Courses (teacher)**

- Create, edit, and delete own courses
- Each course has an enrollment code; students join with it
- See the list of enrolled students

**Assignments (teacher)**

- Title, description, language (Java or Python), deadline, optional attempt limit, time and memory limits
- Test cases: stdin input + expected stdout, each `PUBLIC` (shown to students) or `HIDDEN` (pass/fail only)
- Grading rubric: weighted criteria (tests, static analysis, open answers, manual); weights sum to 100
- Draft/published state; students only see published assignments
- Once an assignment has submissions, its language, rubric and tests are locked, so existing grades always match what they were graded against

**Manual grading (teacher)**

- Rubric criteria of type `MANUAL` cover what tests cannot judge (design, readability)
- The teacher opens any submission, sees the full result including hidden tests and the code, and enters a score and comment per manual criterion
- Until every manual criterion is scored, the grade is shown as provisional

**Submission (student)**

- Paste code into an in-browser editor or upload a source file
- Submit within the deadline and attempt limit
- Live status: queued → running → graded
- Result page: total score, rubric breakdown, per-test pass/fail (hidden tests shown as pass/fail only)
- Submission history per assignment; the best score counts

**AI feedback and hint levels**

- A student requests a hint on a graded submission in which at least one test did not pass
- Three levels, one at a time: 1 nudge, 2 pointer, 3 partial explanation (level 3 is concrete — it explains the fault and the logic the fix needs — but never a full solution)
- The system tracks how many hints were used per submission; the teacher sees it
- Hints never contain a full working solution and never change the score
- A student can flag a hint as wrong or unhelpful; flags are stored, shown to the teacher, and give a measured error rate for the thesis evaluation

**Teacher dashboard**

- Per assignment: submission count, mean/median/min/max, score distribution, per-criterion averages
- Common mistakes: hardest tests, most frequent failure types (compile error, timeout, wrong output), optional AI summary of aggregated mistakes
- Per course: student progress table, grade export to CSV

**Advanced features**

- Real-time updates: submission status pushed over WebSockets
- Plagiarism and similarity detection: token-based fingerprinting with winnowing (as in MOSS), flagged pairs with side-by-side view for the teacher
- Static analysis: cyclomatic complexity, style violations, code smells, feeding a rubric criterion (small suggested default weight, teacher-configurable)
- AI-assisted test generation: the teacher describes the assignment, the LLM proposes edge-case tests, the teacher reviews and accepts
- LLM-graded open answers (e.g. explain the complexity): AI proposes a score and justification, the teacher can override

## AI Feedback Pipeline

Feedback is generated on demand, after the deterministic grading has finished. When a student requests a hint, the backend sends the Python service the assignment description, the student's code, the programming language, the public test results in full, the hidden test results as pass/fail only, and the requested hint level. The service builds a level-specific prompt (the student's code is passed as delimited data, never as instructions), calls Gemini, validates the structured response, runs a solution-leak guard (a response that contains too much code for its level is rejected and regenerated), and returns the hint text. The backend waits for it (bounded by a timeout, never inside a database transaction) and stores it as an `ai_feedback` row; the number of hints used is simply the count of those rows. If the AI service is unavailable the submission and its score are unaffected — only the hint request fails, with a clear error.

Design choice defended in the thesis: the AI explains, the tests grade. Keeping the LLM out of the scoring path makes grades reproducible and auditable, and hiding hidden-test data from the LLM prevents leaking test content through hints.

## Grading & Scoring System

When a student submits code, the backend runs it in a sandbox and scores the result against the rubric.

1. **Submission accepted** — validated (published assignment, before the deadline, attempt limit, size limit, language matches), stored with status `QUEUED`, and the API returns immediately
2. **Sandboxed execution** — a bounded worker pool picks the submission up (`RUNNING`). For each test case, a fresh Docker container is started with no network, a memory limit with swap disabled, CPU and PID limits, a wall-clock timeout, an output size cap, a non-root user, a read-only root filesystem, and all capabilities dropped. Java is compiled first; compile errors are reported as such
3. **Test results recorded** — each test yields `PASSED`, `FAILED`, `TIMEOUT`, `MEMORY_LIMIT`, `RUNTIME_ERROR`, or `COMPILE_ERROR`, with actual output, stderr, and runtime. Stdout is compared with the expected output after whitespace normalization
4. **Rubric scoring** — for each tests criterion, score = sum of passed test weights ÷ sum of all test weights in that criterion; total = sum of criterion weight × criterion score. Other criterion types add to the total the same way: `MANUAL` scores are entered by the teacher, `STATIC_ANALYSIS` scores are computed from code metrics, and `OPEN_ANSWER` scores are AI-proposed and teacher-overridable. Scoring is a pure function of the rubric and the stored per-criterion inputs, and the total is recomputed whenever any of them changes
5. **Graded** — scores stored, status becomes `GRADED`, the frontend is notified. Of several attempts, the best score counts

## Scalability Design

The system is sized for a university course (hundreds of students) but every design decision keeps it open to growth.

**Language configuration** — Supported languages live in a config file (runner image, compile command, run command, source file name). Adding a language means adding an image and a config entry, with no code changes.

**Bounded execution** — Submissions are executed by a fixed-size worker pool so that a burst of submissions near a deadline queues up instead of exhausting the host. Pool size and per-run limits are configuration. The queue is in-process with status persisted in the database; replacing it with a message broker and several worker nodes is a contained change behind the `SandboxRunner` and worker interfaces.

**Stateless services** Both the Spring Boot API and the Python service hold no per-user in-memory state beyond the in-flight queue. The AI service can be scaled horizontally with no coordination.

**LLM cost control** — Hints are on demand, level-capped, and rate-limited per student, so LLM usage is bounded by design and stays within free-tier limits.
