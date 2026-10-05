# frontend/CLAUDE.md

React + TypeScript on Vite, npm. Project-wide rules (gates, defaults, Git safety, PR flow) are in the root `CLAUDE.md`; this file only holds what is specific to `/frontend`.

## Commands (run from `/frontend`)
- Install: `npm ci` (or `npm install` when changing dependencies)
- Dev server: `npm run dev`
- Tests: `npm test` (Vitest, single run)
- Lint: `npm run lint` (oxlint). The oxlint-vs-ESLint question is still open and is a gate at task 0.5c — don't swap linters on your own.
- Build: `npm run build` (runs `tsc -b`, then `vite build`)

## Layout and test setup
- Source is in `src/`; the app is still the unmodified Vite template (`App.tsx`, `main.tsx`).
- Vitest runs on jsdom with Testing Library. `src/test/setup.ts` is the setup file and registers `cleanup` itself.
- Vitest globals are off: import `describe`, `it` and `expect` explicitly in every test file.
- Tests live next to the code (`src/App.test.tsx`).

## Conventions
- There are no real components yet, so no component conventions exist. Don't invent any — they are set by the tasks that introduce the first real components.
- Only `VITE_`-prefixed variables are exposed to client code, and everything in them ends up in the browser bundle: never put a secret there. Local values go in `.env.local`.
- The frontend talks only to the Spring Boot API, never to the Python service (`docs/project-specifications.md`, Architecture).
- Tests are part of the commit that adds the code, and all tests of all three projects must pass before a task counts as finished (root `CLAUDE.md`, Workflow).
- New dependencies are a gate — ask before adding one.
