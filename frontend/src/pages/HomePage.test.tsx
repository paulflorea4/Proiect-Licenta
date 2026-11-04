import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Role } from '../auth/authContext'
import { jsonResponse, stubApi } from '../test/api'

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

function LocationProbe() {
  const location = useLocation()
  return <p data-testid="location">{location.pathname}</p>
}

function renderAt(path: string) {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <LocationProbe />
    </MemoryRouter>,
  )
  return user
}

describe('HomePage: a guest', () => {
  it('reads what the platform does, for students and for teachers', async () => {
    stubApi(rest)

    renderAt('/')

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Grading platform',
      }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 2, name: 'For students' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 2, name: 'For teachers' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/never change your score/)).toBeInTheDocument()
  })

  it('can go on to sign in', async () => {
    stubApi(rest)
    const user = renderAt('/')

    await screen.findByRole('heading', { level: 1, name: 'Grading platform' })
    await user.click(
      within(screen.getByRole('main')).getByRole('link', { name: 'Sign in' }),
    )

    expect(
      screen.getByRole('heading', { level: 1, name: 'Sign in' }),
    ).toBeInTheDocument()
  })

  it('can go on to sign up', async () => {
    stubApi(rest)
    const user = renderAt('/')

    await user.click(
      await within(await screen.findByRole('main')).findByRole('link', {
        name: 'create a student account',
      }),
    )

    expect(
      screen.getByRole('heading', { level: 1, name: 'Sign up' }),
    ).toBeInTheDocument()
  })
})

describe('HomePage: a signed-in user goes to their role’s home', () => {
  it.each([
    ['STUDENT', '/courses', 'Courses'],
    ['TEACHER', '/courses', 'Courses'],
    ['ADMIN', '/admin/users', 'Users'],
  ] as const)('a %s goes to %s', async (role, path, heading) => {
    stubApi(rest, () => jsonResponse(200, userWith(role)))

    renderAt('/')

    expect(
      await screen.findByRole('heading', { level: 1, name: heading }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(path)
    expect(
      screen.queryByRole('heading', { name: 'Grading platform' }),
    ).not.toBeInTheDocument()
  })

  it('while GET /auth/me is open it waits, showing no guest page', () => {
    stubApi(rest, () => new Promise<Response>(() => {}))

    renderAt('/')

    expect(screen.getByRole('status')).toHaveTextContent(
      'Checking your sign-in…',
    )
    expect(screen.queryByRole('heading')).not.toBeInTheDocument()
  })
})

describe('/admin/users, the admin’s home', () => {
  it('is for admins', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('ADMIN')))

    renderAt('/admin/users')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Users' }),
    ).toBeInTheDocument()
  })

  it.each(['STUDENT', 'TEACHER'] as const)(
    'a %s is sent through / to their own home',
    async (role) => {
      stubApi(rest, () => jsonResponse(200, userWith(role)))

      renderAt('/admin/users')

      expect(
        await screen.findByRole('heading', { level: 1, name: 'Courses' }),
      ).toBeInTheDocument()
      expect(screen.getByTestId('location')).toHaveTextContent('/courses')
    },
  )

  it('a guest is sent to sign in', async () => {
    stubApi(rest)

    renderAt('/admin/users')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Sign in' }),
    ).toBeInTheDocument()
  })
})
