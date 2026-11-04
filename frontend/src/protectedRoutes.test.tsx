import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { API, jsonResponse, stubApi } from './test/api'

// 8.6b: the redirect of a logged-out user, through the real App (its real routes, guards and
// sign-in page). The guard's own cases on stand-in routes are in auth/routeGuards.test.tsx (8.3c).

const rest = vi.fn<typeof fetch>()

afterEach(() => {
  rest.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

// Test-only: where the router is, and a Back button, inside the same router.
function Probe() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <p data-testid="location">{location.pathname + location.search}</p>
      <p data-testid="from">
        {(location.state as { from?: string } | null)?.from ?? 'none'}
      </p>
      <button type="button" onClick={() => navigate(-1)}>
        test: back
      </button>
    </>
  )
}

function renderAt(entries: string[]) {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <App />
      <Probe />
    </MemoryRouter>,
  )
  return user
}

function signInHeading() {
  return screen.findByRole('heading', { level: 1, name: 'Sign in' })
}

describe('a logged-out user on a guarded route', () => {
  it.each([
    '/courses',
    '/courses?page=2',
    '/admin/users',
    '/admin/users?page=3',
  ])(
    '%s goes to the real sign-in page, remembering where they were going',
    async (path) => {
      stubApi(rest)

      renderAt([path])

      expect(await signInHeading()).toBeInTheDocument()
      expect(screen.getByLabelText('Email')).toBeInTheDocument()
      expect(screen.getByTestId('location')).toHaveTextContent(/^\/signin$/)
      expect(screen.getByTestId('from')).toHaveTextContent(path)
    },
  )

  it('never asks the API for the guarded page’s data', async () => {
    stubApi(rest)

    renderAt(['/admin/users'])
    await signInHeading()

    // Only AuthProvider's GET /auth/me went out (stubApi answers it); nothing for the page.
    expect(rest).not.toHaveBeenCalled()
  })

  it('replaces the guarded entry in the history, so Back does not bounce off the guard', async () => {
    stubApi(rest)
    const user = renderAt(['/', '/courses'])
    await signInHeading()

    await user.click(screen.getByRole('button', { name: 'test: back' }))

    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Grading platform',
      }),
    ).toBeInTheDocument()
  })

  it.each([
    [
      'no valid cookie (401)',
      () =>
        jsonResponse(401, {
          status: 401,
          code: 'UNAUTHENTICATED',
          message: 'Authentication is required',
        }),
    ],
    [
      'an account deleted since the cookie was set',
      () =>
        jsonResponse(401, {
          status: 401,
          code: 'ACCOUNT_NO_LONGER_EXISTS',
          message: 'Account no longer exists',
        }),
    ],
    [
      'a server error on the sign-in check',
      () =>
        jsonResponse(500, {
          status: 500,
          code: 'INTERNAL_ERROR',
          message: 'An unexpected error occurred',
        }),
    ],
  ])('%s counts as logged out', async (_, me) => {
    stubApi(rest, me)

    renderAt(['/courses'])

    expect(await signInHeading()).toBeInTheDocument()
  })

  it('no network on the sign-in check counts as logged out', async () => {
    vi.stubEnv('VITE_API_BASE_URL', API)
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch')),
    )

    renderAt(['/courses'])

    expect(await signInHeading()).toBeInTheDocument()
  })

  it('is neither redirected nor shown the page while the sign-in check is open', () => {
    stubApi(rest, () => new Promise<Response>(() => {}))

    renderAt(['/admin/users'])

    expect(screen.getByText('Checking your sign-in…')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/users')
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
    expect(rest).not.toHaveBeenCalled()
  })
})
