/// <reference types="vite/client" />

// The VITE_ variables the app reads (see frontend/.env.example). Undefined when no .env file sets them.
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
