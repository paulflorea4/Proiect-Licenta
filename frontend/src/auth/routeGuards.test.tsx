import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { jsonResponse, signedOutMe, stubApi } from '../test/api'
import AuthProvider from './AuthProvider'
import { returnPathOf, useAuth } from './authContext'
import type { Role } from './authContext'
import { ProtectedRoute, RoleRoute } from './routeGuards'

const rest = vi.fn<typeof fetch>()

afterEach(() => {
  rest.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function userWith(role: Role) {
  return {
    id: 1,
    email: `${role.toLowerCase()}@example.com`,
    fullName: 'X',
    role,
  }
}

// Stand-in pages: the guards are tested apart from the real routes (only /courses is guarded so far).
function SignInStandIn() {
  const location = useLocation()
  return (
    <main>
      <h1>Sign in</h1>
      <p data-testid="from">{returnPathOf(location.state) ?? 'none'}</p>
    </main>
  )
}

function PrivatePage() {
  const auth = useAuth()
  return (
    <main>
      <h1>Private</h1>
      <button type="button" onClick={() => void auth.signOut()}>
        Sign out
      </button>
    </main>
  )
}

function renderAt(path: string) {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route path="/" element={<h1>Home</h1>} />
          <Route path="/signin" element={<SignInStandIn />} />
          <Route element={<ProtectedRoute />}>
            <Route path="/private" element={<PrivatePage />} />
          </Route>
          <Route element={<RoleRoute roles={['TEACHER', 'ADMIN']} />}>
            <Route path="/teach" element={<h1>Teach</h1>} />
          </Route>
          <Route element={<RoleRoute roles={['ADMIN']} />}>
            <Route path="/admin" element={<h1>Admin</h1>} />
          </Route>
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
  return user
}

function heading(name: string) {
  return screen.findByRole('heading', { level: 1, name })
}

describe('ProtectedRoute', () => {
  it('waits for GET /auth/me instead of redirecting, then shows the page', async () => {
    let answer: (response: Response) => void = () => {}
    stubApi(
      rest,
      () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    )
    renderAt('/private')

    expect(screen.getByRole('status')).toHaveTextContent(
      'Checking your sign-in…',
    )
    expect(screen.queryByRole('heading')).not.toBeInTheDocument()

    answer(jsonResponse(200, userWith('STUDENT')))

    expect(await heading('Private')).toBeInTheDocument()
  })

  it('shows the page to a signed-in user', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('STUDENT')))

    renderAt('/private')

    expect(await heading('Private')).toBeInTheDocument()
  })

  it('sends a signed-out user to /signin, remembering the path and query', async () => {
    stubApi(rest)

    renderAt('/private?tab=2')

    expect(await heading('Sign in')).toBeInTheDocument()
    expect(screen.getByTestId('from')).toHaveTextContent('/private?tab=2')
    expect(
      screen.queryByRole('heading', { name: 'Private' }),
    ).not.toBeInTheDocument()
  })

  it('signing out on a guarded page leads to /signin', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('STUDENT')))
    rest.mockResolvedValue(new Response(null, { status: 204 }))
    const user = renderAt('/private')
    await heading('Private')

    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await heading('Sign in')).toBeInTheDocument()
  })
})

describe('RoleRoute', () => {
  it.each(['TEACHER', 'ADMIN'] as const)(
    'shows the page to a %s, who has one of the roles',
    async (role) => {
      stubApi(rest, () => jsonResponse(200, userWith(role)))

      renderAt('/teach')

      expect(await heading('Teach')).toBeInTheDocument()
    },
  )

  it('sends a signed-in user without the role to /', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('STUDENT')))

    renderAt('/teach')

    expect(await heading('Home')).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: 'Teach' }),
    ).not.toBeInTheDocument()
  })

  it('a teacher is not an admin: no role implies another', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('TEACHER')))

    renderAt('/admin')

    expect(await heading('Home')).toBeInTheDocument()
  })

  it('sends a signed-out user to /signin, remembering the path', async () => {
    stubApi(rest, signedOutMe)

    renderAt('/teach')

    expect(await heading('Sign in')).toBeInTheDocument()
    expect(screen.getByTestId('from')).toHaveTextContent('/teach')
  })

  it('waits for GET /auth/me instead of redirecting', () => {
    stubApi(rest, () => new Promise<Response>(() => {}))

    renderAt('/teach')

    expect(screen.getByRole('status')).toHaveTextContent(
      'Checking your sign-in…',
    )
    expect(screen.queryByRole('heading')).not.toBeInTheDocument()
  })
})

describe('returnPathOf', () => {
  it.each(['/courses', '/courses?page=2', '/private?tab=2&x=1', '/signup'])(
    'keeps the app path %j',
    (from) => {
      expect(returnPathOf({ from })).toBe(from)
    },
  )

  it.each([
    ['no state', undefined],
    ['null', null],
    ['no from', {}],
    ['a number', { from: 5 }],
    ['another site', { from: 'https://evil.example/x' }],
    ['a protocol-relative URL', { from: '//evil.example/x' }],
    ['a backslash host', { from: '/\\evil.example' }],
    ['a relative path', { from: 'courses' }],
    ['the sign-in page', { from: '/signin' }],
    ['the sign-in page with a query', { from: '/signin?x=1' }],
  ])('ignores %s', (_, state) => {
    expect(returnPathOf(state)).toBeNull()
  })
})
