import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { jsonResponse, stubApi } from '../test/api'

const TEACHER = {
  id: 7,
  email: 'tom@example.com',
  fullName: 'Tom',
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

const LANGUAGES = {
  languages: [
    { id: 'JAVA', minMemoryMb: 128 },
    { id: 'PYTHON', minMemoryMb: 8 },
  ],
}

// Seconds and microseconds that a datetime-local input cannot show: an untouched deadline must
// still be sent back exactly (8.0f).
const STORED_DEADLINE = '2099-03-10T21:30:45.123456Z'

function assignment(overrides: Record<string, unknown> = {}) {
  return {
    id: 42,
    courseId: 5,
    title: 'Sum of two numbers',
    description: 'Read two numbers and print their sum.',
    language: 'PYTHON',
    deadline: STORED_DEADLINE,
    maxAttempts: 3,
    timeLimitMs: 2000,
    memoryLimitMb: 256,
    starterCode: 'print(0)',
    published: false,
    createdAt: '2026-11-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    ...overrides,
  }
}

const rest = vi.fn<typeof fetch>()

afterEach(() => {
  rest.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

type Answer = (body: unknown) => Response | Promise<Response>

/** Signed in as `me`; each call is answered by `"METHOD /path"`, and /languages by default. */
function stubRoutes(me: unknown, routes: Record<string, Answer>) {
  stubApi(rest, () => jsonResponse(200, me))
  rest.mockImplementation(async (input, init) => {
    const url = new URL(String(input))
    const key = `${init?.method ?? 'GET'} ${url.pathname}`
    const answer =
      routes[key] ??
      (key === 'GET /languages'
        ? () => jsonResponse(200, LANGUAGES)
        : undefined)
    if (!answer) {
      throw new Error(`unexpected ${key}`)
    }
    return answer(init?.body ? JSON.parse(String(init.body)) : undefined)
  })
}

function error(
  status: number,
  code: string,
  message: string,
  fieldErrors?: unknown,
) {
  return jsonResponse(status, {
    status,
    code,
    message,
    ...(fieldErrors ? { fieldErrors } : {}),
  })
}

function LocationProbe() {
  const location = useLocation()
  return <p data-testid="location">{location.pathname + location.search}</p>
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

function form() {
  return within(screen.getByRole('form', { name: 'Assignment' }))
}

function field(label: string | RegExp) {
  return form().getByLabelText(label)
}

function sent(method: string) {
  return rest.mock.calls
    .filter(([, init]) => init?.method === method)
    .map(([input, init]) => ({
      path: new URL(String(input)).pathname,
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    }))
}

/** Waits until the language list has arrived (the select is enabled). */
async function languagesLoaded() {
  await waitFor(() => expect(field('Language')).toBeEnabled())
}

async function fillValidNew(user: ReturnType<typeof userEvent.setup>) {
  await languagesLoaded()
  await user.type(field('Title'), '  Sum  ')
  await user.type(field('Description'), 'Add them.')
  await user.selectOptions(field('Language'), 'PYTHON')
  await user.type(field('Deadline (your local time)'), '2099-01-15T10:30')
  await user.type(field('Time limit per test (ms)'), '2000')
  await user.type(field('Memory limit (MB)'), '256')
}

describe('creating an assignment (9.2b)', () => {
  it('offers the configured languages and leaves the limits for the teacher to choose', async () => {
    stubRoutes(TEACHER, {})
    renderAt('/courses/5/assignments/new')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'New assignment' }),
    ).toBeInTheDocument()
    await languagesLoaded()
    const options = within(field('Language') as HTMLElement)
      .getAllByRole('option')
      .map((o) => o.textContent)
    expect(options).toEqual(['Choose a language', 'JAVA', 'PYTHON'])
    expect(field('Time limit per test (ms)')).toHaveValue(null)
    expect(field('Memory limit (MB)')).toHaveValue(null)
    expect(field('Description').tagName).toBe('TEXTAREA')
    expect(field('Starter code (optional)').tagName).toBe('TEXTAREA')
    expect(
      within(screen.getByRole('main')).getByRole('link', {
        name: 'Back to the course',
      }),
    ).toHaveAttribute('href', '/courses/5')
  })

  it('sends the assignment and opens its edit page, saying it is a draft', async () => {
    stubRoutes(TEACHER, {
      'POST /courses/5/assignments': () =>
        jsonResponse(201, assignment({ id: 77, title: 'Sum' })),
      'GET /assignments/77': () =>
        jsonResponse(200, assignment({ id: 77, title: 'Sum' })),
    })
    const user = renderAt('/courses/5/assignments/new')
    await fillValidNew(user)

    await user.click(form().getByRole('button', { name: 'Create assignment' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Sum' }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(
      '/assignments/77/edit',
    )
    expect(screen.getByText(/created as a draft/)).toHaveAttribute(
      'role',
      'status',
    )
    expect(sent('POST')).toEqual([
      {
        path: '/courses/5/assignments',
        body: {
          title: 'Sum',
          description: 'Add them.',
          language: 'PYTHON',
          deadline: new Date('2099-01-15T10:30').toISOString(),
          maxAttempts: null,
          timeLimitMs: 2000,
          memoryLimitMb: 256,
          starterCode: null,
        },
      },
    ])
  })

  it('sends the attempts and starter code when given', async () => {
    stubRoutes(TEACHER, {
      'POST /courses/5/assignments': () =>
        jsonResponse(201, assignment({ id: 77 })),
      'GET /assignments/77': () => jsonResponse(200, assignment({ id: 77 })),
    })
    const user = renderAt('/courses/5/assignments/new')
    await fillValidNew(user)
    await user.type(field('Attempts allowed (empty for unlimited)'), '5')
    await user.type(
      field('Starter code (optional)'),
      'def solve():{Enter}    pass',
    )

    await user.click(form().getByRole('button', { name: 'Create assignment' }))

    await screen.findByText(/created as a draft/)
    expect(sent('POST')[0].body).toMatchObject({
      maxAttempts: 5,
      starterCode: 'def solve():\n    pass',
    })
  })

  it('checks every field before sending anything', async () => {
    stubRoutes(TEACHER, {})
    const user = renderAt('/courses/5/assignments/new')
    await languagesLoaded()

    await user.click(form().getByRole('button', { name: 'Create assignment' }))

    expect(field('Title')).toHaveAccessibleDescription('Enter a title')
    expect(field('Description')).toHaveAccessibleDescription(
      'Enter a description',
    )
    expect(field('Language')).toHaveAccessibleDescription('Choose a language')
    expect(field('Deadline (your local time)')).toHaveAccessibleDescription(
      'Enter the date and time of the deadline',
    )
    expect(field('Time limit per test (ms)')).toHaveAccessibleDescription(
      'Use a whole number from 1 to 10000',
    )
    expect(field('Memory limit (MB)')).toHaveAccessibleDescription(
      'Use a whole number from 1 to 1024',
    )
    expect(sent('POST')).toEqual([])
  })

  it('checks the ranges, and the language’s memory minimum', async () => {
    stubRoutes(TEACHER, {})
    const user = renderAt('/courses/5/assignments/new')
    await fillValidNew(user)
    await user.selectOptions(field('Language'), 'JAVA')
    await user.clear(field('Memory limit (MB)'))
    await user.type(field('Memory limit (MB)'), '64')
    await user.clear(field('Time limit per test (ms)'))
    await user.type(field('Time limit per test (ms)'), '10001')
    await user.type(field('Attempts allowed (empty for unlimited)'), '0')

    await user.click(form().getByRole('button', { name: 'Create assignment' }))

    expect(field('Memory limit (MB)')).toHaveAccessibleDescription(
      'JAVA needs at least 128 MB',
    )
    expect(field('Time limit per test (ms)')).toHaveAccessibleDescription(
      'Use a whole number from 1 to 10000',
    )
    expect(
      field('Attempts allowed (empty for unlimited)'),
    ).toHaveAccessibleDescription(
      'Use a whole number from 1 to 100, or leave it empty',
    )
    expect(sent('POST')).toEqual([])
  })

  it.each([
    [
      'DEADLINE_NOT_IN_FUTURE',
      'The deadline must be in the future',
      'Deadline (your local time)',
    ],
    [
      'DEADLINE_TOO_FAR',
      'The deadline is too far ahead',
      'Deadline (your local time)',
    ],
    ['UNSUPPORTED_LANGUAGE', 'This language is not supported', 'Language'],
    [
      'MEMORY_LIMIT_BELOW_LANGUAGE_MINIMUM',
      'The memory limit is too low',
      'Memory limit (MB)',
    ],
  ])('the backend’s %s goes on its field', async (code, message, label) => {
    stubRoutes(TEACHER, {
      'POST /courses/5/assignments': () => error(400, code, message),
    })
    const user = renderAt('/courses/5/assignments/new')
    await fillValidNew(user)

    await user.click(form().getByRole('button', { name: 'Create assignment' }))

    await waitFor(() =>
      expect(field(label)).toHaveAccessibleDescription(message),
    )
    expect(screen.getByTestId('location')).toHaveTextContent(
      '/courses/5/assignments/new',
    )
  })

  it('the backend’s field errors go on their fields, anything else above the button', async () => {
    let calls = 0
    stubRoutes(TEACHER, {
      'POST /courses/5/assignments': () =>
        ++calls === 1
          ? error(400, 'VALIDATION_FAILED', 'Some fields are invalid', [
              { field: 'starterCode', message: 'must not contain NUL' },
            ])
          : error(404, 'COURSE_NOT_FOUND', 'Course not found'),
    })
    const user = renderAt('/courses/5/assignments/new')
    await fillValidNew(user)

    await user.click(form().getByRole('button', { name: 'Create assignment' }))
    await waitFor(() =>
      expect(field('Starter code (optional)')).toHaveAccessibleDescription(
        'must not contain NUL',
      ),
    )

    await user.click(form().getByRole('button', { name: 'Create assignment' }))
    expect(await form().findByRole('alert')).toHaveTextContent(
      'Course not found',
    )
  })

  it('a language list that cannot be loaded is said on the field', async () => {
    stubRoutes(TEACHER, {
      'GET /languages': () => Promise.reject(new TypeError('Failed to fetch')),
    })
    renderAt('/courses/5/assignments/new')

    await waitFor(() =>
      expect(field('Language')).toHaveAccessibleDescription(
        /Could not reach the server/,
      ),
    )
  })

  it('disables the button while creating', async () => {
    stubRoutes(TEACHER, {
      'POST /courses/5/assignments': () => new Promise<Response>(() => {}),
    })
    const user = renderAt('/courses/5/assignments/new')
    await fillValidNew(user)

    await user.click(form().getByRole('button', { name: 'Create assignment' }))

    expect(
      form().getByRole('button', { name: 'Creating assignment…' }),
    ).toBeDisabled()
  })

  it('a malformed course id is "not found" and asks for nothing', async () => {
    stubRoutes(TEACHER, {})
    renderAt('/courses/abc/assignments/new')

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Course not found',
      }),
    ).toBeInTheDocument()
    expect(rest).not.toHaveBeenCalled()
  })
})

describe('changing an assignment (9.2b)', () => {
  it('shows the stored values in the form', async () => {
    stubRoutes(TEACHER, {
      'GET /assignments/42': () => jsonResponse(200, assignment()),
    })
    renderAt('/assignments/42/edit')

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Sum of two numbers',
      }),
    ).toBeInTheDocument()
    await languagesLoaded()
    expect(field('Title')).toHaveValue('Sum of two numbers')
    expect(field('Description')).toHaveValue(
      'Read two numbers and print their sum.',
    )
    expect(field('Language')).toHaveValue('PYTHON')
    const local = new Date(STORED_DEADLINE)
    const pad = (n: number) => String(n).padStart(2, '0')
    expect(field('Deadline (your local time)')).toHaveValue(
      `${local.getFullYear()}-${pad(local.getMonth() + 1)}-${pad(local.getDate())}T${pad(local.getHours())}:${pad(local.getMinutes())}`,
    )
    expect(field('Attempts allowed (empty for unlimited)')).toHaveValue(3)
    expect(field('Time limit per test (ms)')).toHaveValue(2000)
    expect(field('Memory limit (MB)')).toHaveValue(256)
    expect(field('Starter code (optional)')).toHaveValue('print(0)')
    expect(
      within(screen.getByRole('main')).getByRole('link', {
        name: 'Back to the course',
      }),
    ).toHaveAttribute('href', '/courses/5')
  })

  it('saves, sending an untouched deadline exactly as stored', async () => {
    stubRoutes(TEACHER, {
      'GET /assignments/42': () => jsonResponse(200, assignment()),
      'PUT /assignments/42': (body) =>
        jsonResponse(200, { ...assignment(), ...(body as object) }),
    })
    const user = renderAt('/assignments/42/edit')
    await languagesLoaded()
    await user.clear(field('Title'))
    await user.type(field('Title'), 'Sum, take two')

    await user.click(form().getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByText('Saved.')).toHaveAttribute('role', 'status')
    expect(sent('PUT')).toEqual([
      {
        path: '/assignments/42',
        body: {
          title: 'Sum, take two',
          description: 'Read two numbers and print their sum.',
          language: 'PYTHON',
          deadline: STORED_DEADLINE,
          maxAttempts: 3,
          timeLimitMs: 2000,
          memoryLimitMb: 256,
          starterCode: 'print(0)',
        },
      },
    ])
    expect(
      screen.getByRole('heading', { level: 1, name: 'Sum, take two' }),
    ).toBeInTheDocument()
  })

  it('a changed deadline is sent as the instant typed', async () => {
    stubRoutes(TEACHER, {
      'GET /assignments/42': () => jsonResponse(200, assignment()),
      'PUT /assignments/42': (body) =>
        jsonResponse(200, { ...assignment(), ...(body as object) }),
    })
    const user = renderAt('/assignments/42/edit')
    await languagesLoaded()
    await user.clear(field('Deadline (your local time)'))
    await user.type(field('Deadline (your local time)'), '2099-04-01T08:00')

    await user.click(form().getByRole('button', { name: 'Save changes' }))

    await screen.findByText('Saved.')
    expect(sent('PUT')[0].body).toMatchObject({
      deadline: new Date('2099-04-01T08:00').toISOString(),
    })
  })

  it('a language lock goes on the language, a limits lock on both limits', async () => {
    let calls = 0
    stubRoutes(TEACHER, {
      'GET /assignments/42': () => jsonResponse(200, assignment()),
      'PUT /assignments/42': () =>
        ++calls === 1
          ? error(
              409,
              'ASSIGNMENT_LANGUAGE_LOCKED',
              'The language can no longer be changed',
            )
          : error(
              409,
              'ASSIGNMENT_LIMITS_LOCKED',
              'The limits can no longer be changed',
            ),
    })
    const user = renderAt('/assignments/42/edit')
    await languagesLoaded()

    await user.click(form().getByRole('button', { name: 'Save changes' }))
    await waitFor(() =>
      expect(field('Language')).toHaveAccessibleDescription(
        'The language can no longer be changed',
      ),
    )

    await user.click(form().getByRole('button', { name: 'Save changes' }))
    await waitFor(() =>
      expect(field('Time limit per test (ms)')).toHaveAccessibleDescription(
        'The limits can no longer be changed',
      ),
    )
    expect(field('Memory limit (MB)')).toHaveAccessibleDescription(
      'The limits can no longer be changed',
    )
  })

  it('an unchanged memory limit below the language’s minimum is not checked again (7.1c)', async () => {
    stubRoutes(TEACHER, {
      'GET /assignments/42': () =>
        jsonResponse(200, assignment({ language: 'JAVA', memoryLimitMb: 64 })),
      'PUT /assignments/42': (body) =>
        jsonResponse(200, { ...assignment(), ...(body as object) }),
    })
    const user = renderAt('/assignments/42/edit')
    await languagesLoaded()

    await user.click(form().getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByText('Saved.')).toBeInTheDocument()
  })

  it('a language no longer configured stays selected, so it can be sent back unchanged', async () => {
    stubRoutes(TEACHER, {
      'GET /assignments/42': () =>
        jsonResponse(200, assignment({ language: 'RUST' })),
      'PUT /assignments/42': (body) =>
        jsonResponse(200, { ...assignment(), ...(body as object) }),
    })
    const user = renderAt('/assignments/42/edit')
    await languagesLoaded()
    expect(field('Language')).toHaveValue('RUST')

    await user.click(form().getByRole('button', { name: 'Save changes' }))

    await screen.findByText('Saved.')
    expect(sent('PUT')[0].body).toMatchObject({ language: 'RUST' })
  })

  it('an assignment that does not exist or is not the teacher’s is "not found"', async () => {
    stubRoutes(TEACHER, {
      'GET /assignments/42': () =>
        error(404, 'ASSIGNMENT_NOT_FOUND', 'Assignment not found'),
    })
    renderAt('/assignments/42/edit')

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Assignment not found',
      }),
    ).toBeInTheDocument()
  })

  it('a malformed id asks for nothing', async () => {
    stubRoutes(TEACHER, {})
    renderAt('/assignments/x1/edit')

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Assignment not found',
      }),
    ).toBeInTheDocument()
    expect(rest).not.toHaveBeenCalled()
  })

  it('an admin may change an assignment', async () => {
    stubRoutes(ADMIN, {
      'GET /assignments/42': () => jsonResponse(200, assignment()),
    })
    renderAt('/assignments/42/edit')

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Sum of two numbers',
      }),
    ).toBeInTheDocument()
  })
})

describe('publishing (9.2b)', () => {
  function publishing() {
    return within(screen.getByRole('region', { name: 'Publishing' }))
  }

  it('publishes a draft; its language and limits are then locked on the form', async () => {
    stubRoutes(TEACHER, {
      'GET /assignments/42': () => jsonResponse(200, assignment()),
      'POST /assignments/42/publish': () =>
        jsonResponse(200, assignment({ published: true })),
    })
    const user = renderAt('/assignments/42/edit')
    await languagesLoaded()
    expect(
      await screen.findByText('Draft: students cannot see it.'),
    ).toBeInTheDocument()

    await user.click(publishing().getByRole('button', { name: 'Publish' }))

    expect(
      await publishing().findByText(/Published: students/),
    ).toBeInTheDocument()
    expect(field('Language')).toBeDisabled()
    expect(field('Time limit per test (ms)')).toBeDisabled()
    expect(field('Memory limit (MB)')).toBeDisabled()
    expect(field('Title')).toBeEnabled()
    expect(
      screen.getByText(/cannot change while the assignment is published/),
    ).toBeInTheDocument()
    expect(sent('POST')).toEqual([
      { path: '/assignments/42/publish', body: undefined },
    ])
  })

  it('a refused publish is shown and the assignment stays a draft', async () => {
    stubRoutes(TEACHER, {
      'GET /assignments/42': () => jsonResponse(200, assignment()),
      'POST /assignments/42/publish': () =>
        error(
          409,
          'RUBRIC_WEIGHTS_INVALID',
          'The rubric weights must add up to 100 before the assignment can be published',
        ),
    })
    const user = renderAt('/assignments/42/edit')
    await screen.findByText('Draft: students cannot see it.')

    await user.click(publishing().getByRole('button', { name: 'Publish' }))

    expect(await publishing().findByRole('alert')).toHaveTextContent(
      'The rubric weights must add up to 100',
    )
    expect(
      publishing().getByText('Draft: students cannot see it.'),
    ).toBeInTheDocument()
  })

  it('unpublishes a published assignment, unlocking its language and limits', async () => {
    stubRoutes(TEACHER, {
      'GET /assignments/42': () =>
        jsonResponse(200, assignment({ published: true })),
      'POST /assignments/42/unpublish': () =>
        jsonResponse(200, assignment({ published: false })),
    })
    const user = renderAt('/assignments/42/edit')
    await screen.findByText(/Published: students/)
    expect(field('Memory limit (MB)')).toBeDisabled()

    await user.click(publishing().getByRole('button', { name: 'Unpublish' }))

    expect(
      await publishing().findByText('Draft: students cannot see it.'),
    ).toBeInTheDocument()
    await languagesLoaded()
    expect(field('Memory limit (MB)')).toBeEnabled()
  })

  it('a published assignment sends its language and limits as they are', async () => {
    stubRoutes(TEACHER, {
      'GET /assignments/42': () =>
        jsonResponse(200, assignment({ published: true })),
      'PUT /assignments/42': (body) =>
        jsonResponse(200, {
          ...assignment({ published: true }),
          ...(body as object),
        }),
    })
    const user = renderAt('/assignments/42/edit')
    await screen.findByText(/Published: students/)
    await user.clear(field('Title'))
    await user.type(field('Title'), 'Renamed')

    await user.click(form().getByRole('button', { name: 'Save changes' }))

    await screen.findByText('Saved.')
    expect(sent('PUT')[0].body).toMatchObject({
      language: 'PYTHON',
      timeLimitMs: 2000,
      memoryLimitMb: 256,
    })
  })
})

describe('the course page’s links to the assignment pages (9.2b)', () => {
  const COURSE = {
    id: 5,
    title: 'Algorithms',
    description: null,
    teacherId: 7,
    createdAt: '2026-11-01T10:00:00Z',
  }
  const LIST = {
    items: [assignment()],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  }

  it('a teacher gets "New assignment" and a link to change each assignment', async () => {
    stubRoutes(TEACHER, {
      'GET /courses/5': () => jsonResponse(200, COURSE),
      'GET /courses/5/assignments': () => jsonResponse(200, LIST),
    })
    const user = renderAt('/courses/5')
    const main = within(await screen.findByRole('main'))

    expect(
      await main.findByRole('link', { name: 'New assignment' }),
    ).toHaveAttribute('href', '/courses/5/assignments/new')
    expect(
      main.getByRole('link', { name: 'Sum of two numbers' }),
    ).toHaveAttribute('href', '/assignments/42/edit')

    stubRoutes(TEACHER, {})
    await user.click(main.getByRole('link', { name: 'New assignment' }))
    expect(
      await screen.findByRole('heading', { level: 1, name: 'New assignment' }),
    ).toBeInTheDocument()
  })

  it('an admin can change an assignment but not create one', async () => {
    stubRoutes(ADMIN, {
      'GET /courses/5': () => jsonResponse(200, COURSE),
      'GET /courses/5/assignments': () => jsonResponse(200, LIST),
    })
    renderAt('/courses/5')
    const main = within(await screen.findByRole('main'))

    expect(
      await main.findByRole('link', { name: 'Sum of two numbers' }),
    ).toBeInTheDocument()
    expect(
      main.queryByRole('link', { name: 'New assignment' }),
    ).not.toBeInTheDocument()
  })

  it('a student gets neither', async () => {
    stubRoutes(STUDENT, {
      'GET /courses/5': () => jsonResponse(200, COURSE),
      'GET /courses/5/assignments': () =>
        jsonResponse(200, {
          ...LIST,
          items: [assignment({ published: undefined })],
        }),
      'GET /assignments/42/my-grade': () =>
        jsonResponse(200, { assignmentId: 42, best: null }),
    })
    renderAt('/courses/5')
    const main = within(await screen.findByRole('main'))

    expect(await main.findByText('Sum of two numbers')).toBeInTheDocument()
    expect(
      main.queryByRole('link', { name: 'Sum of two numbers' }),
    ).not.toBeInTheDocument()
    expect(
      main.queryByRole('link', { name: 'New assignment' }),
    ).not.toBeInTheDocument()
  })
})
