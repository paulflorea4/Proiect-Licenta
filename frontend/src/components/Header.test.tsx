import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Role } from '../auth/authContext'
import { API, jsonResponse, stubApi } from '../test/api'

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
    fullName: 'Ana Pop',
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

function header() {
  return within(screen.getByRole('banner'))
}

async function menuLabels() {
  const nav = await screen.findByRole('navigation', { name: 'Main' })
  return within(nav)
    .getAllByRole('link')
    .map((link) => link.textContent)
}

describe('Header: the menu', () => {
  it('a guest gets sign in and sign up, and no sign-out', async () => {
    stubApi(rest)

    renderAt('/')

    expect(await menuLabels()).toEqual(['Sign in', 'Sign up'])
    expect(
      header().queryByRole('button', { name: 'Sign out' }),
    ).not.toBeInTheDocument()
  })

  it.each([
    ['STUDENT', ['Courses']],
    ['TEACHER', ['Courses']],
    ['ADMIN', ['Users', 'Courses']],
  ] as const)('a %s gets %j', async (role, labels) => {
    stubApi(rest, () => jsonResponse(200, userWith(role)))

    renderAt('/courses')

    expect(await menuLabels()).toEqual(labels)
  })

  it('shows who is signed in, with their role', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('TEACHER')))

    renderAt('/courses')

    expect(
      await header().findByText('Signed in as Ana Pop (Teacher)'),
    ).toBeInTheDocument()
    expect(
      header().getByRole('button', { name: 'Sign out' }),
    ).toBeInTheDocument()
  })

  it('shows no menu while GET /auth/me is open, only the name', () => {
    stubApi(rest, () => new Promise<Response>(() => {}))

    renderAt('/')

    expect(
      header().getByRole('link', { name: 'Grading platform' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(header().queryByRole('button')).not.toBeInTheDocument()
  })

  it('marks the current page in the menu', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('ADMIN')))

    renderAt('/admin/users')

    await screen.findByRole('heading', { level: 1, name: 'Users' })
    const nav = within(screen.getByRole('navigation', { name: 'Main' }))
    expect(nav.getByRole('link', { name: 'Users' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(nav.getByRole('link', { name: 'Courses' })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('a menu link goes to its page', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('ADMIN')))
    const user = renderAt('/admin/users')
    await screen.findByRole('heading', { level: 1, name: 'Users' })

    await user.click(
      within(screen.getByRole('navigation', { name: 'Main' })).getByRole(
        'link',
        { name: 'Courses' },
      ),
    )

    expect(
      screen.getByRole('heading', { level: 1, name: 'Courses' }),
    ).toBeInTheDocument()
  })

  it('is on every page, the not-found page included, and its name links home', async () => {
    stubApi(rest)
    const user = renderAt('/nothing-here')
    await screen.findByRole('heading', { level: 1, name: 'Page not found' })

    await user.click(header().getByRole('link', { name: 'Grading platform' }))

    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
    // The header holds no h1: each page keeps exactly one.
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })
})

describe('Header: signing out', () => {
  it('posts /auth/signout; on a guarded page the guard then sends the user to sign in', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('STUDENT')))
    rest.mockResolvedValue(new Response(null, { status: 204 }))
    const user = renderAt('/courses')
    await screen.findByRole('heading', { level: 1, name: 'Courses' })

    await user.click(header().getByRole('button', { name: 'Sign out' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Sign in' }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/signin$/)
    expect(await menuLabels()).toEqual(['Sign in', 'Sign up'])
    expect(
      header().queryByRole('button', { name: 'Sign out' }),
    ).not.toBeInTheDocument()
    const [url, init] = rest.mock.calls[0]
    expect(String(url)).toBe(`${API}/auth/signout`)
    expect(init!.method).toBe('POST')
    expect((init!.headers as Record<string, string>)['X-Requested-With']).toBe(
      'XMLHttpRequest',
    )
  })

  it('a failed sign-out says so and keeps the user signed in', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('STUDENT')))
    rest.mockRejectedValue(new TypeError('Failed to fetch'))
    const user = renderAt('/courses')
    await screen.findByRole('heading', { level: 1, name: 'Courses' })

    await user.click(header().getByRole('button', { name: 'Sign out' }))

    expect(await header().findByRole('alert')).toHaveTextContent(
      'Could not sign out. Please try again.',
    )
    expect(
      header().getByText('Signed in as Ana Pop (Student)'),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 1, name: 'Courses' }),
    ).toBeInTheDocument()
  })

  it('the button is disabled while signing out, so it is sent once', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('STUDENT')))
    let answer: (response: Response) => void = () => {}
    rest.mockReturnValue(
      new Promise((resolve) => {
        answer = resolve
      }),
    )
    const user = renderAt('/courses')
    await screen.findByRole('heading', { level: 1, name: 'Courses' })

    await user.click(header().getByRole('button', { name: 'Sign out' }))
    const button = header().getByRole('button', { name: 'Signing out…' })
    expect(button).toBeDisabled()
    await user.click(button)

    expect(rest).toHaveBeenCalledTimes(1)
    answer(new Response(null, { status: 204 }))
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Sign in' }),
    ).toBeInTheDocument()
  })

  it('on a public page the user stays there, now as a guest', async () => {
    stubApi(rest, () => jsonResponse(200, userWith('STUDENT')))
    rest.mockResolvedValue(new Response(null, { status: 204 }))
    const user = renderAt('/nothing-here')
    await screen.findByRole('heading', { level: 1, name: 'Page not found' })

    await user.click(header().getByRole('button', { name: 'Sign out' }))

    expect(await menuLabels()).toEqual(['Sign in', 'Sign up'])
    expect(screen.getByTestId('location')).toHaveTextContent('/nothing-here')
    expect(
      screen.getByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument()
  })
})
