import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { jsonResponse, stubApi } from '../test/api'

const TEACHER = {
  id: 7,
  email: 'tom@example.com',
  fullName: 'Tom Teacher',
  role: 'TEACHER',
}
const ADMIN = {
  id: 1,
  email: 'root@example.com',
  fullName: 'Root',
  role: 'ADMIN',
}
const STUDENT = {
  id: 9,
  email: 'sam@example.com',
  fullName: 'Sam',
  role: 'STUDENT',
}

function course(id: number, title: string, enrollCode: string) {
  return {
    id,
    title,
    description: null,
    teacherId: TEACHER.id,
    enrollCode,
    createdAt: '2026-11-01T10:00:00Z',
  }
}

const ALGORITHMS = course(1, 'Algorithms', 'ABCD2345')
const GRAPHS = course(2, 'Graphs', 'WXYZ6789')

function page(
  items: unknown[],
  number = 0,
  totalPages = items.length === 0 ? 0 : 1,
  totalElements = items.length,
) {
  return { items, page: number, size: 20, totalElements, totalPages }
}

const rest = vi.fn<typeof fetch>()

afterEach(() => {
  rest.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/**
 * Signed in as `me`; `list(page)` answers GET /courses?page=N, `create(body)` POST /courses and
 * `enroll(body)` POST /courses/enroll.
 */
function stubCourses(
  me: unknown,
  list: (pageIndex: number) => Response | Promise<Response>,
  create: (body: unknown) => Response | Promise<Response> = () =>
    jsonResponse(500, { status: 500, code: 'INTERNAL_ERROR', message: 'x' }),
  enroll: (body: unknown) => Response | Promise<Response> = () =>
    jsonResponse(500, { status: 500, code: 'INTERNAL_ERROR', message: 'x' }),
) {
  stubApi(rest, () => jsonResponse(200, me))
  rest.mockImplementation(async (input, init) => {
    const url = new URL(String(input))
    if (url.pathname === '/courses' && (init?.method ?? 'GET') === 'GET') {
      return list(Number(url.searchParams.get('page')))
    }
    if (url.pathname === '/courses' && init?.method === 'POST') {
      return create(JSON.parse(String(init.body)))
    }
    if (url.pathname === '/courses/enroll' && init?.method === 'POST') {
      return enroll(JSON.parse(String(init.body)))
    }
    throw new Error(`unexpected ${init?.method} ${url}`)
  })
}

function LocationProbe() {
  const location = useLocation()
  return <p data-testid="location">{location.pathname + location.search}</p>
}

function renderAt(path = '/courses') {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <LocationProbe />
    </MemoryRouter>,
  )
  return user
}

function calls(method: string) {
  return rest.mock.calls.filter(
    ([, init]) => (init?.method ?? 'GET') === method,
  )
}

async function table() {
  return within(await screen.findByRole('table'))
}

describe('the teacher course list (9.1a)', () => {
  it('lists the teacher’s courses with their enrollment codes, asking page 0', async () => {
    stubCourses(TEACHER, () => jsonResponse(200, page([ALGORITHMS, GRAPHS])))
    renderAt()

    const rows = await table()
    expect(rows.getByRole('caption')).toHaveTextContent(
      'Your courses, oldest first',
    )
    const algorithms = within(rows.getByRole('row', { name: /Algorithms/ }))
    expect(algorithms.getByText('ABCD2345')).toBeInTheDocument()
    expect(
      algorithms.getByRole('button', {
        name: 'Copy the enrollment code of Algorithms',
      }),
    ).toBeInTheDocument()
    expect(rows.getByText('WXYZ6789')).toBeInTheDocument()
    expect(screen.getByText('Page 1 of 1 (2 courses)')).toBeInTheDocument()

    const [[input, init]] = calls('GET')
    expect(String(input)).toBe('http://api.test/courses?page=0')
    expect(init?.credentials).toBe('include')
  })

  it('says it is loading while the list has not arrived', async () => {
    stubCourses(TEACHER, () => new Promise<Response>(() => {}))
    renderAt()

    expect(await screen.findByText('Loading courses…')).toBeInTheDocument()
  })

  it('a failed load is shown', async () => {
    stubCourses(TEACHER, () => Promise.reject(new TypeError('Failed to fetch')))
    renderAt()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not reach the server',
    )
  })

  it('a teacher without courses is told to create one, and sees the form', async () => {
    stubCourses(TEACHER, () => jsonResponse(200, page([])))
    renderAt()

    expect(
      await screen.findByText(
        'You have no courses yet. Create your first one below.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('navigation', { name: 'Pages' }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('form', { name: 'Create a course' }),
    ).toBeInTheDocument()
  })

  it('goes to the next and previous page, keeping the page in the URL', async () => {
    stubCourses(TEACHER, (pageIndex) =>
      jsonResponse(
        200,
        page(
          [course(pageIndex + 1, `Course ${pageIndex + 1}`, 'CODE2345')],
          pageIndex,
          2,
          21,
        ),
      ),
    )
    const user = renderAt()
    const pages = within(
      await screen.findByRole('navigation', { name: 'Pages' }),
    )
    expect(pages.getByRole('button', { name: 'Previous' })).toBeDisabled()

    await user.click(pages.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('Course 2')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/courses?page=2')
    expect(screen.getByText('Page 2 of 2 (21 courses)')).toBeInTheDocument()
    expect(String(calls('GET')[1][0])).toBe('http://api.test/courses?page=1')
  })
})

describe('copying an enrollment code (9.1a)', () => {
  it('puts the code on the clipboard and says so', async () => {
    stubCourses(TEACHER, () => jsonResponse(200, page([ALGORITHMS])))
    const user = renderAt()
    const rows = await table()

    await user.click(
      rows.getByRole('button', {
        name: 'Copy the enrollment code of Algorithms',
      }),
    )

    expect(await navigator.clipboard.readText()).toBe('ABCD2345')
    expect(rows.getByRole('status')).toHaveTextContent('Copied')
  })

  it('a refused clipboard says to copy it by hand, and the code stays shown', async () => {
    stubCourses(TEACHER, () => jsonResponse(200, page([ALGORITHMS])))
    const user = renderAt()
    const rows = await table()
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(
      new DOMException('denied', 'NotAllowedError'),
    )

    await user.click(
      rows.getByRole('button', {
        name: 'Copy the enrollment code of Algorithms',
      }),
    )

    expect(await rows.findByRole('status')).toHaveTextContent(
      'Could not copy: select the code and copy it',
    )
    expect(rows.getByText('ABCD2345')).toBeInTheDocument()
  })
})

describe('creating a course (9.1a)', () => {
  function form() {
    return within(screen.getByRole('form', { name: 'Create a course' }))
  }

  it('sends the trimmed title and no description, then shows the new code and reloads the list', async () => {
    const created = course(3, 'Compilers', 'NEWC0DE2')
    let lists = 0
    stubCourses(
      TEACHER,
      () => {
        lists += 1
        return jsonResponse(
          200,
          page(lists === 1 ? [ALGORITHMS] : [ALGORITHMS, created]),
        )
      },
      () => jsonResponse(201, created),
    )
    const user = renderAt()
    await table()

    await user.type(form().getByLabelText('Title'), '  Compilers  ')
    await user.click(form().getByRole('button', { name: 'Create course' }))

    const fresh = within(
      await screen.findByRole('region', { name: 'New course' }),
    )
    expect(fresh.getByText(/“Compilers” is created/)).toHaveAttribute(
      'role',
      'status',
    )
    expect(fresh.getByText('NEWC0DE2')).toBeInTheDocument()
    expect(
      fresh.getByRole('button', {
        name: 'Copy the enrollment code of Compilers',
      }),
    ).toBeInTheDocument()
    const [[, init]] = calls('POST')
    expect(JSON.parse(String(init?.body))).toEqual({
      title: 'Compilers',
      description: null,
    })
    expect(form().getByLabelText('Title')).toHaveValue('')
    await waitFor(() =>
      expect(screen.getByText('Page 1 of 1 (2 courses)')).toBeInTheDocument(),
    )
    expect(calls('GET')).toHaveLength(2)
  })

  it('sends a description as typed, in a multi-line field', async () => {
    stubCourses(
      TEACHER,
      () => jsonResponse(200, page([])),
      () => jsonResponse(201, course(3, 'Compilers', 'NEWC0DE2')),
    )
    const user = renderAt()
    await screen.findByText(/You have no courses yet/)
    const description = form().getByLabelText('Description (optional)')
    expect(description.tagName).toBe('TEXTAREA')

    await user.type(form().getByLabelText('Title'), 'Compilers')
    await user.type(description, 'Lexing{Enter}Parsing')
    await user.click(form().getByRole('button', { name: 'Create course' }))

    await screen.findByRole('region', { name: 'New course' })
    expect(JSON.parse(String(calls('POST')[0][1]?.body))).toEqual({
      title: 'Compilers',
      description: 'Lexing\nParsing',
    })
  })

  it('checks the title and the description before sending anything', async () => {
    stubCourses(TEACHER, () => jsonResponse(200, page([])))
    const user = renderAt()
    await screen.findByText(/You have no courses yet/)

    await user.type(form().getByLabelText('Title'), '   ')
    await user.click(form().getByRole('button', { name: 'Create course' }))
    expect(form().getByLabelText('Title')).toHaveAccessibleDescription(
      'Enter a title',
    )
    expect(form().getByLabelText('Title')).toHaveAttribute(
      'aria-invalid',
      'true',
    )

    await user.clear(form().getByLabelText('Title'))
    await user.click(form().getByLabelText('Title'))
    await user.paste('x'.repeat(256))
    await user.click(form().getByLabelText('Description (optional)'))
    await user.paste('y'.repeat(5001))
    await user.click(form().getByRole('button', { name: 'Create course' }))

    expect(form().getByLabelText('Title')).toHaveAccessibleDescription(
      'Use at most 255 characters',
    )
    expect(
      form().getByLabelText('Description (optional)'),
    ).toHaveAccessibleDescription('Use at most 5000 characters')
    expect(calls('POST')).toHaveLength(0)
  })

  it('accepts a title of exactly 255 characters', async () => {
    stubCourses(
      TEACHER,
      () => jsonResponse(200, page([])),
      () => jsonResponse(201, course(3, 'x'.repeat(255), 'NEWC0DE2')),
    )
    const user = renderAt()
    await screen.findByText(/You have no courses yet/)

    await user.click(form().getByLabelText('Title'))
    await user.paste('x'.repeat(255))
    await user.click(form().getByRole('button', { name: 'Create course' }))

    await screen.findByRole('region', { name: 'New course' })
    expect(calls('POST')).toHaveLength(1)
  })

  it('shows the backend’s field errors on their fields', async () => {
    stubCourses(
      TEACHER,
      () => jsonResponse(200, page([])),
      () =>
        jsonResponse(400, {
          status: 400,
          code: 'VALIDATION_FAILED',
          message: 'Some fields are invalid',
          fieldErrors: [{ field: 'title', message: 'must not contain NUL' }],
        }),
    )
    const user = renderAt()
    await screen.findByText(/You have no courses yet/)

    await user.type(form().getByLabelText('Title'), 'Compilers')
    await user.click(form().getByRole('button', { name: 'Create course' }))

    await waitFor(() =>
      expect(form().getByLabelText('Title')).toHaveAccessibleDescription(
        'must not contain NUL',
      ),
    )
    expect(form().getByLabelText('Title')).toHaveValue('Compilers')
    expect(
      screen.queryByRole('region', { name: 'New course' }),
    ).not.toBeInTheDocument()
  })

  it('shows any other refusal above the button and keeps what was typed', async () => {
    stubCourses(
      TEACHER,
      () => jsonResponse(200, page([])),
      () =>
        jsonResponse(401, {
          status: 401,
          code: 'ACCOUNT_NO_LONGER_EXISTS',
          message: 'Account no longer exists',
        }),
    )
    const user = renderAt()
    await screen.findByText(/You have no courses yet/)

    await user.type(form().getByLabelText('Title'), 'Compilers')
    await user.click(form().getByRole('button', { name: 'Create course' }))

    expect(await form().findByRole('alert')).toHaveTextContent(
      'Account no longer exists',
    )
    expect(form().getByLabelText('Title')).toHaveValue('Compilers')
  })

  it('disables the button while the course is being created', async () => {
    stubCourses(
      TEACHER,
      () => jsonResponse(200, page([])),
      () => new Promise<Response>(() => {}),
    )
    const user = renderAt()
    await screen.findByText(/You have no courses yet/)

    await user.type(form().getByLabelText('Title'), 'Compilers')
    await user.click(form().getByRole('button', { name: 'Create course' }))

    expect(
      form().getByRole('button', { name: 'Creating course…' }),
    ).toBeDisabled()
    expect(calls('POST')).toHaveLength(1)
  })
})

describe('the courses page for the other roles (9.1a)', () => {
  it('an admin sees every course with its code, and no create form', async () => {
    stubCourses(ADMIN, () => jsonResponse(200, page([ALGORITHMS, GRAPHS])))
    renderAt()

    const rows = await table()
    expect(rows.getByRole('caption')).toHaveTextContent(
      'All courses, oldest first',
    )
    expect(rows.getByText('ABCD2345')).toBeInTheDocument()
    expect(
      screen.queryByRole('form', { name: 'Create a course' }),
    ).not.toBeInTheDocument()
  })

  it('an admin with no courses anywhere is told so', async () => {
    stubCourses(ADMIN, () => jsonResponse(200, page([])))
    renderAt()

    expect(
      await screen.findByText('There are no courses yet.'),
    ).toBeInTheDocument()
  })
})

/** A course as a student receives it: no enrollment code (3.1b). */
function studentCourse(
  id: number,
  title: string,
  description: string | null = null,
) {
  return {
    id,
    title,
    description,
    teacherId: TEACHER.id,
    createdAt: '2026-11-01T10:00:00Z',
  }
}

describe('the student course list and join form (9.1b)', () => {
  function joinForm() {
    return within(screen.getByRole('form', { name: 'Join a course' }))
  }

  it('lists the courses the student is in, without codes, asking page 0', async () => {
    stubCourses(STUDENT, () =>
      jsonResponse(
        200,
        page([
          studentCourse(1, 'Algorithms', 'Sorting and graphs'),
          studentCourse(2, 'Graphs'),
        ]),
      ),
    )
    renderAt()

    const list = within(await screen.findByRole('list', { name: 'My courses' }))
    expect(list.getAllByRole('listitem')).toHaveLength(2)
    expect(list.getByText('Algorithms')).toBeInTheDocument()
    expect(list.getByText('Sorting and graphs')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /Copy/ }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('form', { name: 'Create a course' }),
    ).not.toBeInTheDocument()
    expect(screen.getByText('Page 1 of 1 (2 courses)')).toBeInTheDocument()
    expect(String(calls('GET')[0][0])).toBe('http://api.test/courses?page=0')
  })

  it('a student in no course is told how to join, next to the form', async () => {
    stubCourses(STUDENT, () => jsonResponse(200, page([])))
    renderAt()

    expect(
      await screen.findByText(
        'You are not in any course yet. Ask your teacher for the course’s enrollment code and enter it above to join.',
      ),
    ).toBeInTheDocument()
    expect(joinForm().getByLabelText('Enrollment code')).toBeInTheDocument()
    expect(
      screen.queryByRole('list', { name: 'My courses' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('navigation', { name: 'Pages' }),
    ).not.toBeInTheDocument()
  })

  it('a failed load is shown, and the join form stays usable', async () => {
    stubCourses(STUDENT, () => Promise.reject(new TypeError('Failed to fetch')))
    renderAt()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not reach the server',
    )
    expect(
      joinForm().getByRole('button', { name: 'Join course' }),
    ).toBeEnabled()
  })

  it('joins with the trimmed code, says so, empties the field and reloads the list', async () => {
    let lists = 0
    stubCourses(
      STUDENT,
      () => {
        lists += 1
        return jsonResponse(
          200,
          page(lists === 1 ? [] : [studentCourse(1, 'Algorithms')]),
        )
      },
      undefined,
      () => jsonResponse(200, studentCourse(1, 'Algorithms')),
    )
    const user = renderAt()
    await screen.findByText(/You are not in any course yet/)

    await user.type(joinForm().getByLabelText('Enrollment code'), '  abcd2345 ')
    await user.click(joinForm().getByRole('button', { name: 'Join course' }))

    expect(await screen.findByText('You are in “Algorithms”.')).toHaveAttribute(
      'role',
      'status',
    )
    expect(JSON.parse(String(calls('POST')[0][1]?.body))).toEqual({
      code: 'abcd2345',
    })
    expect(joinForm().getByLabelText('Enrollment code')).toHaveValue('')
    const list = within(await screen.findByRole('list', { name: 'My courses' }))
    expect(list.getByText('Algorithms')).toBeInTheDocument()
    expect(calls('GET')).toHaveLength(2)
  })

  it('checks the code before sending anything', async () => {
    stubCourses(STUDENT, () => jsonResponse(200, page([])))
    const user = renderAt()
    await screen.findByText(/You are not in any course yet/)
    const field = joinForm().getByLabelText('Enrollment code')

    await user.type(field, '   ')
    await user.click(joinForm().getByRole('button', { name: 'Join course' }))
    expect(field).toHaveAccessibleDescription(
      'Enter the enrollment code your teacher gave you',
    )
    expect(field).toHaveAttribute('aria-invalid', 'true')

    await user.clear(field)
    await user.type(field, 'A'.repeat(21))
    await user.click(joinForm().getByRole('button', { name: 'Join course' }))
    expect(field).toHaveAccessibleDescription(
      'An enrollment code has at most 20 characters',
    )

    expect(calls('POST')).toHaveLength(0)
  })

  it('an unknown code is shown on the field, keeping what was typed', async () => {
    stubCourses(
      STUDENT,
      () => jsonResponse(200, page([])),
      undefined,
      () =>
        jsonResponse(404, {
          status: 404,
          code: 'ENROLL_CODE_NOT_FOUND',
          message: 'No course has this enrollment code',
        }),
    )
    const user = renderAt()
    await screen.findByText(/You are not in any course yet/)
    const field = joinForm().getByLabelText('Enrollment code')

    await user.type(field, 'WRONG234')
    await user.click(joinForm().getByRole('button', { name: 'Join course' }))

    await waitFor(() =>
      expect(field).toHaveAccessibleDescription(
        'No course has this enrollment code',
      ),
    )
    expect(field).toHaveValue('WRONG234')
    expect(screen.queryByText(/You are in/)).not.toBeInTheDocument()
  })

  it('a field error from the backend goes on the field', async () => {
    stubCourses(
      STUDENT,
      () => jsonResponse(200, page([])),
      undefined,
      () =>
        jsonResponse(400, {
          status: 400,
          code: 'VALIDATION_FAILED',
          message: 'Some fields are invalid',
          fieldErrors: [{ field: 'code', message: 'must not contain NUL' }],
        }),
    )
    const user = renderAt()
    await screen.findByText(/You are not in any course yet/)

    await user.type(joinForm().getByLabelText('Enrollment code'), 'ABCD2345')
    await user.click(joinForm().getByRole('button', { name: 'Join course' }))

    await waitFor(() =>
      expect(
        joinForm().getByLabelText('Enrollment code'),
      ).toHaveAccessibleDescription('must not contain NUL'),
    )
  })

  it('any other refusal is shown above the button', async () => {
    // 9.0d: a user promoted since signing in is refused by their stored role.
    stubCourses(
      STUDENT,
      () => jsonResponse(200, page([])),
      undefined,
      () =>
        jsonResponse(403, {
          status: 403,
          code: 'ACCESS_DENIED',
          message: 'You do not have permission to do this',
        }),
    )
    const user = renderAt()
    await screen.findByText(/You are not in any course yet/)

    await user.type(joinForm().getByLabelText('Enrollment code'), 'ABCD2345')
    await user.click(joinForm().getByRole('button', { name: 'Join course' }))

    expect(await joinForm().findByRole('alert')).toHaveTextContent(
      'You do not have permission to do this',
    )
    expect(joinForm().getByLabelText('Enrollment code')).toHaveValue('ABCD2345')
  })

  it('disables the button while joining', async () => {
    stubCourses(
      STUDENT,
      () => jsonResponse(200, page([])),
      undefined,
      () => new Promise<Response>(() => {}),
    )
    const user = renderAt()
    await screen.findByText(/You are not in any course yet/)

    await user.type(joinForm().getByLabelText('Enrollment code'), 'ABCD2345')
    await user.click(joinForm().getByRole('button', { name: 'Join course' }))

    expect(joinForm().getByRole('button', { name: 'Joining…' })).toBeDisabled()
    expect(calls('POST')).toHaveLength(1)
  })

  it('pages through the student’s courses with the page in the URL', async () => {
    stubCourses(STUDENT, (pageIndex) =>
      jsonResponse(
        200,
        page(
          [studentCourse(pageIndex + 1, `Course ${pageIndex + 1}`)],
          pageIndex,
          2,
          21,
        ),
      ),
    )
    const user = renderAt()
    const pages = within(
      await screen.findByRole('navigation', { name: 'Pages' }),
    )

    await user.click(pages.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('Course 2')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/courses?page=2')
    expect(String(calls('GET')[1][0])).toBe('http://api.test/courses?page=1')
  })

  it('teachers and admins get no join form', async () => {
    stubCourses(TEACHER, () => jsonResponse(200, page([ALGORITHMS])))
    renderAt()

    await screen.findByRole('table')
    expect(
      screen.queryByRole('form', { name: 'Join a course' }),
    ).not.toBeInTheDocument()
  })
})
