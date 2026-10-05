# frontend/CLAUDE.md

React + TypeScript on Vite, npm. Project-wide rules (gates, defaults, Git safety, PR flow) are in the root `CLAUDE.md`; this file only holds what is specific to `/frontend`.

## Commands (run from `/frontend`)

- Install: `npm ci` (or `npm install` when changing dependencies)
- Dev server: `npm run dev`
- Tests: `npm test` (Vitest, single run)
- Lint: `npm run lint` (ESLint, flat config in `eslint.config.js`; chosen over the template's oxlint at 0.5c). Don't add another linter on your own.
- Format: `npm run format` (Prettier, writes) / `npm run format:check` (verify only). Config in `.prettierrc.json` (no semicolons, single quotes, `endOfLine: "auto"` so CRLF checkouts on Windows pass).
- Build: `npm run build` (runs `tsc -b`, then `vite build`)
- Dev server runs at `http://localhost:5173`, the origin already listed in `CORS_ALLOWED_ORIGINS` in `backend/.env.example`.

## Layout and test setup

- Source is in `src/`; the app is still the unmodified Vite template (`App.tsx`, `main.tsx`).
- Vitest runs on jsdom with Testing Library. `src/test/setup.ts` is the setup file and registers `cleanup` itself.
- Vitest globals are off: import `describe`, `it` and `expect` explicitly in every test file.
- Tests live next to the code (`src/App.test.tsx`).
- TypeScript is strict about unused code and syntax (`tsconfig.app.json`): `noUnusedLocals`/`noUnusedParameters`, `verbatimModuleSyntax` (use `import type` for type-only imports) and `erasableSyntaxOnly` (no enums or parameter properties). `npm run build` fails on violations; ESLint (`*.ts`/`*.tsx` only) and Prettier run separately.

## Conventions

- There are no real components yet, so no component conventions exist. Don't invent any — they are set by the tasks that introduce the first real components.
- Only `VITE_`-prefixed variables are exposed to client code, and everything in them ends up in the browser bundle: never put a secret there. Local values go in `.env.local`. The only variable so far is `VITE_API_BASE_URL` (the Spring Boot base URL, default `http://localhost:8080`, no trailing slash — see `frontend/.env.example`).
- The frontend talks only to the Spring Boot API, never to the Python service (`docs/project-specifications.md`, Architecture).
- Tests are part of the commit that adds the code, and all tests of all three projects must pass before a task counts as finished (root `CLAUDE.md`, Workflow).
- New dependencies are a gate — ask before adding one.
