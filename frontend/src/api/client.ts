// The one way the frontend calls the Spring Boot API (8.2a): every later page goes through
// `api.*` / `apiRequest`, never raw `fetch`. Any failure is thrown as an `ApiError`.

/** One invalid request field and the reason (`ErrorResponse.FieldError` in the backend). */
export interface FieldError {
  field: string
  message: string
}

/** The backend's one error body (2.6a, `ErrorResponse`). */
export interface ApiErrorBody {
  status: number
  code: string
  message: string
  fieldErrors?: FieldError[]
}

/** Codes produced here, for failures the backend could not answer with its error body. */
export const NETWORK_ERROR = 'NETWORK_ERROR'
export const UNEXPECTED_RESPONSE = 'UNEXPECTED_RESPONSE'

/**
 * A failed API call. `code` is the backend's `ErrorCode` name (branch on it, never on `message`),
 * or `NETWORK_ERROR` (no response; `status` is 0) or `UNEXPECTED_RESPONSE` (a response that is not
 * the standard body, such as Tomcat's own HTML error page). `message` is fixed text, safe to show.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]

  constructor(
    status: number,
    code: string,
    message: string,
    fieldErrors: FieldError[] = [],
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

/** True when `error` is an `ApiError`, and has `code` if one is given. */
export function isApiError(error: unknown, code?: string): error is ApiError {
  return (
    error instanceof ApiError && (code === undefined || error.code === code)
  )
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

/** The backend's `AuthCookie.CSRF_HEADER` (8.2d); any non-blank value is accepted. */
export const CSRF_HEADER = 'X-Requested-With'
export const CSRF_HEADER_VALUE = 'XMLHttpRequest'

export interface ApiRequestOptions {
  method?: HttpMethod
  /** Sent as JSON, except a `FormData` (multipart), which is sent as is. */
  body?: unknown
  headers?: Record<string, string>
  signal?: AbortSignal
}

/** `VITE_API_BASE_URL` without trailing slashes; read on every call, so tests can stub it. */
export function apiBaseUrl(): string {
  const base = import.meta.env.VITE_API_BASE_URL?.trim()
  if (!base) {
    throw new Error(
      'VITE_API_BASE_URL is not set: copy frontend/.env.example to frontend/.env.local',
    )
  }
  return base.replace(/\/+$/, '')
}

/**
 * Calls `path` (starting with `/`, relative to the API base URL) and returns the parsed JSON body,
 * or `undefined` for an empty one (204). Throws an `ApiError` for an error status, a network
 * failure or an unreadable body; an aborted call rethrows the abort error unchanged.
 */
export async function apiRequest<T>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<T> {
  if (!path.startsWith('/') || path.startsWith('//')) {
    throw new Error(`API path must start with a single '/': ${path}`)
  }
  const { method = 'GET', body, signal } = options
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...options.headers,
  }
  if (method !== 'GET') {
    // The backend refuses a cookie-authenticated write without it (8.2d, CSRF). Set last, so a
    // caller's headers cannot drop it; not on GET, where it would only add a CORS preflight.
    headers[CSRF_HEADER] = CSRF_HEADER_VALUE
  }
  let requestBody: BodyInit | undefined
  if (body instanceof FormData) {
    // The browser sets the multipart Content-Type with its boundary.
    requestBody = body
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    requestBody = JSON.stringify(body)
  }

  // Outside the try: a missing base URL is a configuration error, not a network one.
  const url = apiBaseUrl() + path
  let response: Response
  try {
    response = await fetch(url, {
      method,
      headers,
      body: requestBody,
      // The API is another origin (:8080 vs :5173 in dev): without this the browser neither
      // sends the httpOnly auth cookie nor keeps the one signin sets (8.2b, 8.2e).
      credentials: 'include',
      signal,
    })
  } catch (error) {
    if (signal?.aborted) {
      throw error
    }
    throw new ApiError(0, NETWORK_ERROR, 'Could not reach the server')
  }

  const text = await readText(response, signal)
  if (!response.ok) {
    throw errorFrom(response.status, text)
  }
  if (text === '') {
    return undefined as T
  }
  try {
    return JSON.parse(text) as T
  } catch {
    throw unexpected(response.status)
  }
}

/** Shorthands for `apiRequest` with the method set. */
export const api = {
  get: <T>(
    path: string,
    options?: Omit<ApiRequestOptions, 'method' | 'body'>,
  ) => apiRequest<T>(path, { ...options, method: 'GET' }),
  post: <T>(
    path: string,
    body?: unknown,
    options?: Omit<ApiRequestOptions, 'method' | 'body'>,
  ) => apiRequest<T>(path, { ...options, method: 'POST', body }),
  put: <T>(
    path: string,
    body?: unknown,
    options?: Omit<ApiRequestOptions, 'method' | 'body'>,
  ) => apiRequest<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(
    path: string,
    body?: unknown,
    options?: Omit<ApiRequestOptions, 'method' | 'body'>,
  ) => apiRequest<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T = void>(
    path: string,
    options?: Omit<ApiRequestOptions, 'method' | 'body'>,
  ) => apiRequest<T>(path, { ...options, method: 'DELETE' }),
}

async function readText(
  response: Response,
  signal: AbortSignal | undefined,
): Promise<string> {
  try {
    return await response.text()
  } catch (error) {
    if (signal?.aborted) {
      throw error
    }
    // The connection broke while the body was arriving.
    throw new ApiError(0, NETWORK_ERROR, 'Could not reach the server')
  }
}

function errorFrom(status: number, text: string): ApiError {
  let body: unknown
  try {
    body = JSON.parse(text)
  } catch {
    return unexpected(status)
  }
  if (!isErrorBody(body)) {
    return unexpected(status)
  }
  // The HTTP status is authoritative; the body only repeats it.
  return new ApiError(status, body.code, body.message, body.fieldErrors ?? [])
}

function unexpected(status: number): ApiError {
  return new ApiError(
    status,
    UNEXPECTED_RESPONSE,
    'The server sent an unexpected response',
  )
}

function isErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const body = value as Record<string, unknown>
  return (
    typeof body.status === 'number' &&
    typeof body.code === 'string' &&
    body.code !== '' &&
    typeof body.message === 'string' &&
    (body.fieldErrors === undefined ||
      (Array.isArray(body.fieldErrors) && body.fieldErrors.every(isFieldError)))
  )
}

function isFieldError(value: unknown): value is FieldError {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const fieldError = value as Record<string, unknown>
  return (
    typeof fieldError.field === 'string' &&
    typeof fieldError.message === 'string'
  )
}
