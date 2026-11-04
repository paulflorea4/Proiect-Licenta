import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { API, jsonResponse, stubApi } from '../test/api'

const ROOT = {
  id: 1,
  email: 'root@example.com',
  fullName: 'Root Admin',
  role: 'ADMIN',
}
const ROWS = [
  { id: 1, email: 'root@example.com', role: 'ADMIN' },
  { id: 2, email: 'bob@example.com', role: 'STUDENT' },
  { id: 3, email: 'tom@example.com', role: 'TEACHER' },
]

function page(
  items: unknown[],
  number = 0,
  totalPages = 1,
  totalElements = items.length,
) {
  return { items, page: number, size: 20, totalElements, totalPages }
}

const rest = vi.fn<typeof fetch>()

afterEach(() => {
  rest.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

/** Signed in as ROOT; `list(page)` answers GET /admin/users?page=N, `patch` the role change. */
function stubAdmin(
  list: (pageIndex: number) => Response | Promise<Response>,
  patch: (id: number, body: unknown) => Response | Promise<Response> = () =>
    jsonResponse(500, { status: 500, code: 'INTERNAL_ERROR', message: 'x' }),
) {
  stubApi(rest, () => jsonResponse(200, ROOT))
  rest.mockImplementation(async (input, init) => {
    const url = new URL(String(input))
    if (url.pathname === '/admin/users' && init?.method === 'GET') {
      return list(Number(url.searchParams.get('page')))
    }
    const match = url.pathname.match(/^\/admin\/users\/(\d+)\/role$/)
    if (match && init?.method === 'PATCH') {
      return patch(Number(match[1]), JSON.parse(String(init.body)))
    }
    throw new Error(`unexpected ${init?.method} ${url}`)
  })
}

function LocationProbe() {
  const location = useLocation()
  return <p data-testid="location">{location.pathname + location.search}</p>
}

function renderAt(path = '/admin/users') {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <LocationProbe />
    </MemoryRouter>,
  )
  return user
}

function roleOf(email: string) {
  return screen.getByRole('combobox', { name: `Role of ${email}` })
}

function calls(method: string) {
  return rest.mock.calls.filter(([, init]) => init?.method === method)
}

describe('AdminUsersPage: the list', () => {
  it('lists every account with its role, oldest first, asking page 0 with the cookie', async () => {
    stubAdmin(() => jsonResponse(200, page(ROWS)))

    renderAt()

    const table = await screen.findByRole('table', {
      name: 'All accounts, oldest first',
    })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('root@example.com (you)'),
      expect.stringContaining('bob@example.com'),
      expect.stringContaining('tom@example.com'),
    ])
    expect(roleOf('root@example.com')).toHaveValue('ADMIN')
    expect(roleOf('bob@example.com')).toHaveValue('STUDENT')
    expect(roleOf('tom@example.com')).toHaveValue('TEACHER')
    expect(screen.getByText('Page 1 of 1 (3 users)')).toBeInTheDocument()
    const [url, init] = calls('GET')[0]
    expect(String(url)).toBe(`${API}/admin/users?page=0`)
    expect(init!.credentials).toBe('include')
  })

  it('says it is loading while the list has not arrived', async () => {
    stubAdmin(() => new Promise<Response>(() => {}))

    renderAt()

    expect(await screen.findByText('Loading users…')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('a failed load is shown', async () => {
    stubAdmin(() =>
      jsonResponse(500, {
        status: 500,
        code: 'INTERNAL_ERROR',
        message: 'An unexpected error occurred',
      }),
    )

    renderAt()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'An unexpected error occurred',
    )
  })

  it('an empty page says so', async () => {
    stubAdmin(() => jsonResponse(200, page([], 4, 2, 25)))

    renderAt('/admin/users?page=5')

    expect(
      await screen.findByText('No users on this page.'),
    ).toBeInTheDocument()
  })
})

describe('AdminUsersPage: pages', () => {
  it('goes to the next and previous page, keeping the page in the URL', async () => {
    stubAdmin((n) =>
      jsonResponse(
        200,
        page(
          [{ id: 10 + n, email: `p${n}@example.com`, role: 'STUDENT' }],
          n,
          3,
          41,
        ),
      ),
    )
    const user = renderAt()
    await screen.findByText('p0@example.com')
    // Re-read each time: the page list (and its nav) is rendered anew for every page.
    const pages = {
      getByRole: (...args: Parameters<typeof screen.getByRole>) =>
        within(screen.getByRole('navigation', { name: 'Pages' })).getByRole(
          ...args,
        ),
    }
    expect(pages.getByRole('button', { name: 'Previous' })).toBeDisabled()

    await user.click(pages.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('p1@example.com')).toBeInTheDocument()
    expect(screen.getByText('Page 2 of 3 (41 users)')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(
      '/admin/users?page=2',
    )

    await user.click(pages.getByRole('button', { name: 'Next' }))
    await screen.findByText('p2@example.com')
    expect(pages.getByRole('button', { name: 'Next' })).toBeDisabled()

    await user.click(pages.getByRole('button', { name: 'Previous' }))
    expect(await screen.findByText('p1@example.com')).toBeInTheDocument()
  })

  it.each([
    ['?page=3', 2],
    ['?page=abc', 0],
    ['?page=0', 0],
    ['?page=-2', 0],
    ['?page=1.5', 0],
  ])('the URL %s asks the API for page %i', async (search, expected) => {
    stubAdmin((n) => jsonResponse(200, page(ROWS, n, 5)))

    renderAt(`/admin/users${search}`)

    await screen.findByRole('table')
    expect(String(calls('GET')[0][0])).toBe(
      `${API}/admin/users?page=${expected}`,
    )
  })
})

describe('AdminUsersPage: changing a role', () => {
  it('sends the new role and shows it', async () => {
    stubAdmin(
      () => jsonResponse(200, page(ROWS)),
      (id, body) =>
        jsonResponse(200, {
          id,
          email: 'bob@example.com',
          fullName: 'Bob',
          role: (body as { role: string }).role,
        }),
    )
    const user = renderAt()
    await screen.findByRole('table')

    await user.selectOptions(roleOf('bob@example.com'), 'TEACHER')

    expect(await screen.findByRole('status')).toHaveTextContent(
      'bob@example.com is now Teacher.',
    )
    expect(roleOf('bob@example.com')).toHaveValue('TEACHER')
    const [url, init] = calls('PATCH')[0]
    expect(String(url)).toBe(`${API}/admin/users/2/role`)
    expect(JSON.parse(String(init!.body))).toEqual({ role: 'TEACHER' })
    expect((init!.headers as Record<string, string>)['X-Requested-With']).toBe(
      'XMLHttpRequest',
    )
  })

  it('every selector is disabled while a change is being saved', async () => {
    let answer: (response: Response) => void = () => {}
    stubAdmin(
      () => jsonResponse(200, page(ROWS)),
      () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    )
    const user = renderAt()
    await screen.findByRole('table')

    await user.selectOptions(roleOf('bob@example.com'), 'TEACHER')

    for (const email of ROWS.map((row) => row.email)) {
      expect(roleOf(email)).toBeDisabled()
    }
    answer(
      jsonResponse(200, {
        id: 2,
        email: 'bob@example.com',
        fullName: 'Bob',
        role: 'TEACHER',
      }),
    )
    await screen.findByRole('status')
    expect(roleOf('tom@example.com')).toBeEnabled()
  })

  it('a refusal is shown and the role stays as it was', async () => {
    stubAdmin(
      () => jsonResponse(200, page(ROWS)),
      () =>
        jsonResponse(409, {
          status: 409,
          code: 'LAST_ADMIN',
          message: 'The last remaining admin cannot be demoted',
        }),
    )
    const user = renderAt()
    await screen.findByRole('table')

    await user.selectOptions(roleOf('root@example.com'), 'STUDENT')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The last remaining admin cannot be demoted',
    )
    expect(roleOf('root@example.com')).toHaveValue('ADMIN')
  })

  it('an admin who demotes themselves leaves the admin page for their new home', async () => {
    stubAdmin(
      () => jsonResponse(200, page(ROWS)),
      () => jsonResponse(200, { ...ROOT, role: 'TEACHER' }),
    )
    const user = renderAt()
    await screen.findByRole('table')

    await user.selectOptions(roleOf('root@example.com'), 'TEACHER')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Courses' }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/courses$/)
  })
})
