import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { jsonResponse, stubApi } from './test/api'

const STUDENT = {
  id: 7,
  email: 'ana@example.com',
  fullName: 'Ana Pop',
  role: 'STUDENT',
}

beforeEach(() => {
  // AuthProvider asks GET /auth/me on load (signed out); the routes call nothing else.
  stubApi(vi.fn<typeof fetch>())
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

describe('App routes', () => {
  it.each([
    ['/', 'Grading platform'],
    ['/signin', 'Sign in'],
    ['/signup', 'Sign up'],
  ])('%s shows its page to a guest', async (path, heading) => {
    renderAt(path)

    expect(
      await screen.findByRole('heading', { level: 1, name: heading }),
    ).toBeInTheDocument()
    // Exactly one page at a time: no other route's heading is rendered.
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('/courses shows its page to a signed-in user', async () => {
    stubApi(vi.fn<typeof fetch>(), () => jsonResponse(200, STUDENT))

    renderAt('/courses')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Courses' }),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('/courses sends a signed-out user to sign in (8.3c)', async () => {
    renderAt('/courses')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Sign in' }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { level: 1, name: 'Courses' }),
    ).not.toBeInTheDocument()
  })

  it.each(['/nothing-here', '/courses/1/extra', '/SIGNIN/x'])(
    '%s matches no route and shows the not-found page',
    (path) => {
      renderAt(path)

      expect(
        screen.getByRole('heading', { level: 1, name: 'Page not found' }),
      ).toBeInTheDocument()
    },
  )

  it('the not-found page links back to the start page', async () => {
    const user = userEvent.setup()
    renderAt('/nothing-here')

    await user.click(
      screen.getByRole('link', { name: 'Back to the start page' }),
    )

    expect(
      screen.getByRole('heading', { level: 1, name: 'Grading platform' }),
    ).toBeInTheDocument()
  })
})
