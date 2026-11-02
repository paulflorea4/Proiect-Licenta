import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  NETWORK_ERROR,
  UNEXPECTED_RESPONSE,
  api,
  apiBaseUrl,
  apiRequest,
  isApiError,
} from './client'

const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  vi.stubEnv('VITE_API_BASE_URL', 'http://api.test')
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function lastCall(): { url: string; init: RequestInit } {
  const [url, init] = fetchMock.mock.calls.at(-1)!
  return { url: String(url), init: init! }
}

async function caught(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise
  } catch (error) {
    return error
  }
  throw new Error('expected the call to fail')
}

describe('apiBaseUrl', () => {
  it('drops trailing slashes', () => {
    vi.stubEnv('VITE_API_BASE_URL', ' http://api.test/// ')

    expect(apiBaseUrl()).toBe('http://api.test')
  })

  it.each(['', '   '])('refuses a blank value %j', (value) => {
    vi.stubEnv('VITE_API_BASE_URL', value)

    expect(() => apiBaseUrl()).toThrow(/VITE_API_BASE_URL is not set/)
  })

  it('refuses an unset value, without calling the network', async () => {
    vi.stubEnv('VITE_API_BASE_URL', undefined)

    await expect(api.get('/auth/me')).rejects.toThrow(
      /VITE_API_BASE_URL is not set/,
    )
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('apiRequest: the request', () => {
  it('calls the path under the base URL, GET, asking for JSON, no body', async () => {
    fetchMock.mockResolvedValue(json(200, {}))

    await apiRequest('/courses?page=0')

    const { url, init } = lastCall()
    expect(url).toBe('http://api.test/courses?page=0')
    expect(init.method).toBe('GET')
    expect(init.body).toBeUndefined()
    expect(init.headers).toEqual({ Accept: 'application/json' })
  })

  it('sends a body as JSON', async () => {
    fetchMock.mockResolvedValue(json(201, {}))

    await api.post('/auth/signup', { email: 'a@b.c', password: 'secret123' })

    const { init } = lastCall()
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({
      Accept: 'application/json',
      'Content-Type': 'application/json',
    })
    expect(init.body).toBe('{"email":"a@b.c","password":"secret123"}')
  })

  it('sends FormData as is, leaving the multipart Content-Type to the browser', async () => {
    fetchMock.mockResolvedValue(json(202, {}))
    const form = new FormData()
    form.append('file', new Blob(['print(1)']), 'main.py')

    await api.post('/assignments/1/submissions', form)

    const { init } = lastCall()
    expect(init.body).toBe(form)
    expect(init.headers).toEqual({ Accept: 'application/json' })
  })

  it('adds the caller’s headers and passes the abort signal', async () => {
    fetchMock.mockResolvedValue(json(200, {}))
    const controller = new AbortController()

    await api.get('/auth/me', {
      headers: { Authorization: 'Bearer t' },
      signal: controller.signal,
    })

    const { init } = lastCall()
    expect(init.headers).toEqual({
      Accept: 'application/json',
      Authorization: 'Bearer t',
    })
    expect(init.signal).toBe(controller.signal)
  })

  it.each([
    ['get', 'GET'],
    ['post', 'POST'],
    ['put', 'PUT'],
    ['patch', 'PATCH'],
    ['delete', 'DELETE'],
  ] as const)('api.%s sends %s', async (name, method) => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await api[name]('/courses/1')

    expect(lastCall().init.method).toBe(method)
  })

  it.each(['courses', 'http://elsewhere.test/x', '//elsewhere.test/x', ''])(
    'refuses the path %j, without calling the network',
    async (path) => {
      await expect(apiRequest(path)).rejects.toThrow(/single '\/'/)
      expect(fetchMock).not.toHaveBeenCalled()
    },
  )
})

describe('apiRequest: a successful response', () => {
  it('returns the parsed JSON body', async () => {
    fetchMock.mockResolvedValue(json(200, { id: 7, role: 'STUDENT' }))

    const user = await api.get<{ id: number; role: string }>('/auth/me')

    expect(user).toEqual({ id: 7, role: 'STUDENT' })
  })

  it('returns undefined for an empty body (204)', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await expect(api.delete('/courses/1')).resolves.toBeUndefined()
  })

  it('a body that is not JSON is UNEXPECTED_RESPONSE', async () => {
    fetchMock.mockResolvedValue(
      new Response('<html>ok</html>', { status: 200 }),
    )

    const error = await caught(api.get('/courses'))

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 200, code: UNEXPECTED_RESPONSE })
  })
})

describe('apiRequest: an error response', () => {
  it('a standard error body becomes an ApiError with its code and message', async () => {
    fetchMock.mockResolvedValue(
      json(409, {
        status: 409,
        code: 'EMAIL_ALREADY_USED',
        message: 'An account with this email already exists',
      }),
    )

    const error = await caught(api.post('/auth/signup', {}))

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      name: 'ApiError',
      status: 409,
      code: 'EMAIL_ALREADY_USED',
      message: 'An account with this email already exists',
      fieldErrors: [],
    })
  })

  it('keeps the field errors of a validation failure', async () => {
    const fieldErrors = [
      { field: 'email', message: 'must not be blank' },
      { field: 'password', message: 'size must be between 8 and 72' },
    ]
    fetchMock.mockResolvedValue(
      json(400, {
        status: 400,
        code: 'VALIDATION_FAILED',
        message: 'The request has invalid fields',
        fieldErrors,
      }),
    )

    const error = await caught(api.post('/auth/signup', {}))

    expect(error).toMatchObject({ code: 'VALIDATION_FAILED', fieldErrors })
  })

  it('takes the status from the response, not the body', async () => {
    fetchMock.mockResolvedValue(
      json(403, { status: 200, code: 'ACCESS_DENIED', message: 'No' }),
    )

    expect(await caught(api.get('/admin/users'))).toMatchObject({
      status: 403,
      code: 'ACCESS_DENIED',
    })
  })

  it.each([
    [
      'an HTML page (Tomcat’s own 400)',
      '<html><body>Bad Request</body></html>',
    ],
    ['an empty body', ''],
    ['JSON of another shape', '{"error":"Bad Request"}'],
    ['JSON that is not an object', '"Bad Request"'],
    ['null', 'null'],
    ['an empty code', '{"status":400,"code":"","message":"x"}'],
    ['a numeric code', '{"status":400,"code":4,"message":"x"}'],
    ['no message', '{"status":400,"code":"MALFORMED_REQUEST"}'],
    ['no status', '{"code":"MALFORMED_REQUEST","message":"x"}'],
    [
      'field errors that are not a list',
      '{"status":400,"code":"VALIDATION_FAILED","message":"x","fieldErrors":{}}',
    ],
    [
      'a field error without a message',
      '{"status":400,"code":"VALIDATION_FAILED","message":"x","fieldErrors":[{"field":"email"}]}',
    ],
  ])('%s is UNEXPECTED_RESPONSE with the real status', async (_, body) => {
    fetchMock.mockResolvedValue(new Response(body, { status: 400 }))

    const error = await caught(api.get('/courses'))

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 400,
      code: UNEXPECTED_RESPONSE,
      message: 'The server sent an unexpected response',
      fieldErrors: [],
    })
  })
})

describe('apiRequest: no response', () => {
  it('a failed fetch is NETWORK_ERROR with status 0', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    const error = await caught(api.get('/courses'))

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 0,
      code: NETWORK_ERROR,
      message: 'Could not reach the server',
    })
  })

  it('a body that breaks off is NETWORK_ERROR', async () => {
    const response = json(200, {})
    vi.spyOn(response, 'text').mockRejectedValue(new TypeError('terminated'))
    fetchMock.mockResolvedValue(response)

    expect(await caught(api.get('/courses'))).toMatchObject({
      status: 0,
      code: NETWORK_ERROR,
    })
  })

  it('an aborted call rethrows the abort error, not an ApiError', async () => {
    const controller = new AbortController()
    controller.abort()
    fetchMock.mockRejectedValue(controller.signal.reason)

    const error = await caught(
      api.get('/courses', { signal: controller.signal }),
    )

    expect(error).toBe(controller.signal.reason)
    expect(error).not.toBeInstanceOf(ApiError)
  })
})

describe('isApiError', () => {
  const error = new ApiError(404, 'COURSE_NOT_FOUND', 'Course not found')

  it('recognises an ApiError, with or without a code', () => {
    expect(isApiError(error)).toBe(true)
    expect(isApiError(error, 'COURSE_NOT_FOUND')).toBe(true)
    expect(isApiError(error, 'NOT_FOUND')).toBe(false)
  })

  it('refuses anything else', () => {
    expect(isApiError(new Error('Course not found'))).toBe(false)
    expect(
      isApiError({ status: 404, code: 'COURSE_NOT_FOUND', message: 'x' }),
    ).toBe(false)
    expect(isApiError(undefined)).toBe(false)
  })
})
