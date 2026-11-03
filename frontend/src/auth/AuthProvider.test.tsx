import { useEffect } from 'react'
import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { API, jsonResponse, stubApi } from '../test/api'
import AuthProvider from './AuthProvider'
import { useAuth } from './authContext'
import type { Auth, User } from './authContext'

const ADA: User = {
  id: 7,
  email: 'ada@example.com',
  fullName: 'Ada Pop',
  role: 'STUDENT',
}
const TOM: User = {
  id: 8,
  email: 'tom@example.com',
  fullName: 'Tom T',
  role: 'TEACHER',
}

const rest = vi.fn<typeof fetch>()
const latest: { auth: Auth | null } = { auth: null }

// The context as the probe last rendered it, for the test to call.
function current(): Auth {
  return latest.auth!
}

// Shows the state and hands the context to the test (from an effect: render stays pure).
function Probe() {
  const auth = useAuth()
  useEffect(() => {
    latest.auth = auth
  })
  return (
    <p data-testid="state">
      {auth.state.status}
      {auth.state.user ? ` ${auth.state.user.email}` : ''}
    </p>
  )
}

function renderProvider() {
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
}

function state() {
  return screen.getByTestId('state').textContent
}

afterEach(() => {
  rest.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

describe('AuthProvider: who is signed in on load', () => {
  beforeEach(() => stubApi(rest))

  it('is loading until GET /auth/me answers, then signed in as that user', async () => {
    let answer: (response: Response) => void = () => {}
    stubApi(
      rest,
      () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    )
    renderProvider()

    expect(state()).toBe('loading')

    await act(async () => answer(jsonResponse(200, ADA)))

    expect(state()).toBe('signedIn ada@example.com')
  })

  it('asks GET /auth/me with the cookie, and nothing else', async () => {
    const spy = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(200, ADA))
    vi.stubEnv('VITE_API_BASE_URL', API)
    vi.stubGlobal('fetch', spy)

    renderProvider()

    await waitFor(() => expect(state()).toBe('signedIn ada@example.com'))
    expect(spy).toHaveBeenCalledTimes(1)
    const [url, init] = spy.mock.calls[0]
    expect(String(url)).toBe(`${API}/auth/me`)
    expect(init!.method).toBe('GET')
    expect(init!.credentials).toBe('include')
  })

  it.each([
    [
      'a 401 (no valid cookie)',
      () =>
        jsonResponse(401, {
          status: 401,
          code: 'UNAUTHENTICATED',
          message: 'x',
        }),
    ],
    [
      'a deleted account',
      () =>
        jsonResponse(401, {
          status: 401,
          code: 'ACCOUNT_NO_LONGER_EXISTS',
          message: 'x',
        }),
    ],
    [
      'a server error',
      () =>
        jsonResponse(500, {
          status: 500,
          code: 'INTERNAL_ERROR',
          message: 'x',
        }),
    ],
    ['an unexpected response', () => new Response('<html/>', { status: 502 })],
  ])('%s means signed out', async (_, me) => {
    stubApi(rest, me)

    renderProvider()

    await waitFor(() => expect(state()).toBe('signedOut'))
  })

  it('a network failure means signed out', async () => {
    vi.stubEnv('VITE_API_BASE_URL', API)
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')),
    )

    renderProvider()

    await waitFor(() => expect(state()).toBe('signedOut'))
  })

  it('useAuth outside the provider is a programming error', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})

    expect(() => render(<Probe />)).toThrow(/inside AuthProvider/)
  })
})

describe('AuthProvider: signing in and out', () => {
  it('signedIn records the user', async () => {
    stubApi(rest)
    renderProvider()
    await waitFor(() => expect(state()).toBe('signedOut'))

    act(() => current().signedIn(TOM))

    expect(state()).toBe('signedIn tom@example.com')
  })

  it('a signin while GET /auth/me is still open wins over its late 401', async () => {
    let answer: (response: Response) => void = () => {}
    stubApi(
      rest,
      () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    )
    renderProvider()

    act(() => current().signedIn(TOM))
    await act(async () =>
      answer(
        jsonResponse(401, {
          status: 401,
          code: 'UNAUTHENTICATED',
          message: 'x',
        }),
      ),
    )

    expect(state()).toBe('signedIn tom@example.com')
  })

  it('signOut posts /auth/signout with the CSRF header, then signed out', async () => {
    stubApi(rest, () => jsonResponse(200, ADA))
    rest.mockResolvedValue(new Response(null, { status: 204 }))
    renderProvider()
    await waitFor(() => expect(state()).toBe('signedIn ada@example.com'))

    await act(() => current().signOut())

    expect(state()).toBe('signedOut')
    const [url, init] = rest.mock.calls[0]
    expect(String(url)).toBe(`${API}/auth/signout`)
    expect(init!.method).toBe('POST')
    expect(init!.credentials).toBe('include')
    expect((init!.headers as Record<string, string>)['X-Requested-With']).toBe(
      'XMLHttpRequest',
    )
  })

  it('a failed signOut is rethrown and the user stays signed in', async () => {
    stubApi(rest, () => jsonResponse(200, ADA))
    rest.mockRejectedValue(new TypeError('Failed to fetch'))
    renderProvider()
    await waitFor(() => expect(state()).toBe('signedIn ada@example.com'))

    let thrown: unknown
    await act(async () => {
      try {
        await current().signOut()
      } catch (error) {
        thrown = error
      }
    })

    expect(thrown).toBeInstanceOf(ApiError)
    expect(state()).toBe('signedIn ada@example.com')
  })
})
