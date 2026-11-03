import { vi } from 'vitest'
import type { Mock } from 'vitest'

export const API = 'http://api.test'

export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** What `GET /auth/me` answers a browser with no valid auth cookie. */
export function signedOutMe(): Response {
  return jsonResponse(401, {
    status: 401,
    code: 'UNAUTHENTICATED',
    message: 'Authentication is required',
  })
}

/**
 * Stubs `VITE_API_BASE_URL` (`API`) and `fetch` for a test that renders the whole `App`:
 * `GET /auth/me`, which `AuthProvider` asks on load (8.3b), answers `me()` (signed out unless
 * given); every other call goes to `rest`, so a page test sees only its own requests. Undo with
 * `vi.unstubAllEnvs()` and `vi.unstubAllGlobals()`.
 */
export function stubApi(
  rest: Mock<typeof fetch>,
  me: () => Response | Promise<Response> = signedOutMe,
) {
  vi.stubEnv('VITE_API_BASE_URL', API)
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) =>
    String(input) === `${API}/auth/me` && (init?.method ?? 'GET') === 'GET'
      ? Promise.resolve(me())
      : rest(input, init),
  )
}
