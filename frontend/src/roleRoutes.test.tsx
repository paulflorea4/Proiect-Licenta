import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation, useNavigate } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { homeOf } from './auth/authContext'
import type { Role } from './auth/authContext'
import { jsonResponse, stubApi } from './test/api'

// 8.6c: RoleRoute keeps a student (and every other role without the right) out of role pages,
// through the real App. The guard's own cases on stand-in routes, a teacher-only one included, are
// in auth/routeGuards.test.tsx (8.3c).

/**
 * Every role-restricted route of the App, with the roles allowed and the heading of its page: add
 * each new one here, so every role is checked against it.
 */
const ROLE_ROUTES: readonly [
  path: string,
  roles: readonly Role[],
  heading: string,
][] = [
  ['/admin/users', ['ADMIN'], 'Users'],
  ['/admin/users?page=3', ['ADMIN'], 'Users'],
  // 9.2b: creating an assignment is teachers only (3.3a); changing one is teachers and admins.
  ['/courses/5/assignments/new', ['TEACHER'], 'New assignment'],
  ['/assignments/5/edit', ['TEACHER', 'ADMIN'], 'Sum of two numbers'],
]

const ROLES: readonly Role[] = ['STUDENT', 'TEACHER', 'ADMIN']

const HOME_HEADINGS: Record<string, string> = {
  '/courses': 'Courses',
  '/admin/users': 'Users',
}

const rest = vi.fn<typeof fetch>()

afterEach(() => {
  rest.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function signedInAs(role: Role) {
  stubApi(rest, () =>
    jsonResponse(200, {
      id: 1,
      email: `${role.toLowerCase()}@example.com`,
      fullName: 'X',
      role,
    }),
  )
  // A page an allowed role opens may load data: an assignment for the edit page (9.2b), the
  // configured languages for the assignment form, an empty list for anything else.
  rest.mockImplementation(async (input) => {
    const path = new URL(String(input)).pathname
    if (/^\/assignments\/\d+$/.test(path)) {
      return jsonResponse(200, {
        id: 5,
        courseId: 1,
        title: 'Sum of two numbers',
        description: 'd',
        language: 'PYTHON',
        deadline: '2099-01-01T00:00:00Z',
        maxAttempts: null,
        timeLimitMs: 2000,
        memoryLimitMb: 256,
        starterCode: null,
        published: false,
      })
    }
    if (path === '/languages') {
      return jsonResponse(200, {
        languages: [{ id: 'PYTHON', minMemoryMb: 8 }],
      })
    }
    return jsonResponse(200, {
      items: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    })
  })
}

/** The requests that went out besides AuthProvider's GET /auth/me, as "METHOD path". */
function pageRequests() {
  return rest.mock.calls.map(
    ([input, init]) =>
      `${init?.method ?? 'GET'} ${new URL(String(input)).pathname}`,
  )
}

// Test-only: where the router is, and a way to navigate inside the app (not a fresh load).
function Probe() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <p data-testid="location">{location.pathname + location.search}</p>
      <button type="button" onClick={() => navigate('/admin/users')}>
        test: open the admin page
      </button>
    </>
  )
}

function renderAt(path: string) {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <Probe />
    </MemoryRouter>,
  )
  return user
}

const CASES = ROLE_ROUTES.flatMap(([path, roles, heading]) =>
  ROLES.map((role) => [path, role, roles.includes(role), heading] as const),
)

describe('role routes, for every role', () => {
  it.each(CASES.filter(([, , allowed]) => allowed))(
    '%s is open to a %s',
    async (path, role, _, heading) => {
      signedInAs(role)

      renderAt(path)

      expect(
        await screen.findByRole('heading', { level: 1, name: heading }),
      ).toBeInTheDocument()
      expect(screen.getByTestId('location')).toHaveTextContent(path)
    },
  )

  it.each(CASES.filter(([, , allowed]) => !allowed))(
    '%s sends a %s to their own home, and its data is never requested',
    async (path, role) => {
      signedInAs(role)

      renderAt(path)

      const home = homeOf(role)
      expect(
        await screen.findByRole('heading', {
          level: 1,
          name: HOME_HEADINGS[home],
        }),
      ).toBeInTheDocument()
      expect(screen.getByTestId('location')).toHaveTextContent(
        new RegExp(`^${home}$`),
      )
      const blocked = new URL(path, 'http://x').pathname
      expect(pageRequests()).not.toContain(`GET ${blocked}`)
    },
  )
})

describe('a student', () => {
  it('who navigates to the admin page inside the app is sent back too, without a request', async () => {
    signedInAs('STUDENT')
    const user = renderAt('/courses')
    await screen.findByRole('heading', { level: 1, name: 'Courses' })

    await user.click(
      screen.getByRole('button', { name: 'test: open the admin page' }),
    )

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Courses' }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/courses$/)
    // Their own home loads their courses (9.1b); the admin list is never asked for.
    expect(pageRequests()).not.toContain('GET /admin/users')
  })

  it('never sees the admin page’s heading, not even for a moment', async () => {
    signedInAs('STUDENT')

    renderAt('/admin/users')

    await screen.findByRole('heading', { level: 1, name: 'Courses' })
    // The guard decides before the page renders: the user list was never loaded, so it never
    // mounted (only the student's own courses are, 9.1b).
    expect(
      screen.queryByRole('heading', { name: 'Users' }),
    ).not.toBeInTheDocument()
    expect(pageRequests()).toEqual(['GET /courses'])
  })
})
