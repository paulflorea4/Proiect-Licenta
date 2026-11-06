import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { jsonResponse, stubApi } from '../test/api'

const STUDENT = {
  id: 9,
  email: 'sam@example.com',
  fullName: 'Sam',
  role: 'STUDENT',
}
const TEACHER = {
  id: 7,
  email: 'tom@example.com',
  fullName: 'Tom',
  role: 'TEACHER',
}

const COURSE = {
  id: 5,
  title: 'Algorithms',
  description: 'Sorting and graphs',
  teacherId: TEACHER.id,
  createdAt: '2026-11-01T10:00:00Z',
}

const FUTURE = '2099-01-15T21:59:00Z'
const PAST = '2001-01-15T21:59:00Z'

function assignment(
  id: number,
  title: string,
  deadline = FUTURE,
  published?: boolean,
) {
  return {
    id,
    courseId: COURSE.id,
    title,
    description: 'd',
    language: 'PYTHON',
    deadline,
    maxAttempts: null,
    timeLimitMs: 2000,
    memoryLimitMb: 256,
    starterCode: null,
    ...(published === undefined ? {} : { published }),
  }
}

function page(
  items: unknown[],
  number = 0,
  totalPages = items.length === 0 ? 0 : 1,
  totalElements = items.length,
) {
  return { items, page: number, size: 20, totalElements, totalPages }
}

function best(totalScore: number, maxScore: number, provisional = false) {
  return {
    submissionId: 1,
    attemptNo: 1,
    totalScore,
    maxScore,
    gradedAt: '2026-11-02T10:00:00Z',
    provisional,
  }
}

const rest = vi.fn<typeof fetch>()

afterEach(() => {
  rest.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

type Handler = (url: URL) => Response | Promise<Response>

/** Signed in as `me`; each GET is answered by the handler of its path (an exact path, or a regex). */
function stubPaths(me: unknown, handlers: [string | RegExp, Handler][]) {
  stubApi(rest, () => jsonResponse(200, me))
  rest.mockImplementation(async (input, init) => {
    const url = new URL(String(input))
    if ((init?.method ?? 'GET') === 'GET') {
      for (const [path, handler] of handlers) {
        if (
          typeof path === 'string'
            ? url.pathname === path
            : path.test(url.pathname)
        ) {
          return handler(url)
        }
      }
    }
    throw new Error(`unexpected ${init?.method} ${url}`)
  })
}

function myGrades(
  byId: Record<number, Response | (() => Response)>,
): [RegExp, Handler] {
  return [
    /^\/assignments\/\d+\/my-grade$/,
    (url) => {
      const id = Number(url.pathname.split('/')[2])
      const answer = byId[id]
      return typeof answer === 'function' ? answer() : answer
    },
  ]
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

function requested() {
  return rest.mock.calls.map(([input]) => {
    const url = new URL(String(input))
    return url.pathname + url.search
  })
}

async function row(name: RegExp) {
  return within(await screen.findByRole('row', { name }))
}

const notFound = () =>
  jsonResponse(404, {
    status: 404,
    code: 'COURSE_NOT_FOUND',
    message: 'Course not found',
  })

describe('the course page for a student (9.2a)', () => {
  it('shows the course and its assignments with deadline and best score', async () => {
    stubPaths(STUDENT, [
      ['/courses/5', () => jsonResponse(200, COURSE)],
      [
        '/courses/5/assignments',
        () =>
          jsonResponse(
            200,
            page([
              assignment(11, 'Sum'),
              assignment(12, 'Sort'),
              assignment(13, 'Graphs'),
            ]),
          ),
      ],
      myGrades({
        11: jsonResponse(200, { assignmentId: 11, best: best(85.5, 100) }),
        12: jsonResponse(200, { assignmentId: 12, best: best(40, 100, true) }),
        13: jsonResponse(200, { assignmentId: 13, best: null }),
      }),
    ])
    renderAt('/courses/5')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Algorithms' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Sorting and graphs')).toBeInTheDocument()
    const table = within(await screen.findByRole('table'))
    expect(
      table.getByRole('columnheader', { name: 'Best score' }),
    ).toBeInTheDocument()
    expect(
      await (await row(/Sum/)).findByText('85.5 / 100'),
    ).toBeInTheDocument()
    expect(
      await (await row(/Sort/)).findByText('40 / 100 (provisional)'),
    ).toBeInTheDocument()
    expect(
      await (await row(/Graphs/)).findByText('Not graded yet'),
    ).toBeInTheDocument()
    const deadline = (await row(/Sum/)).getByText(
      (_, element) => element?.tagName === 'TIME',
    )
    expect(deadline).toHaveAttribute('dateTime', FUTURE)
    expect(deadline.textContent).not.toBe('')
    expect(screen.getByText('Page 1 of 1 (3 assignments)')).toBeInTheDocument()
    expect(requested()).toEqual(
      expect.arrayContaining([
        '/courses/5',
        '/courses/5/assignments?page=0',
        '/assignments/11/my-grade',
        '/assignments/12/my-grade',
        '/assignments/13/my-grade',
      ]),
    )
  })

  it('marks a passed deadline as closed', async () => {
    stubPaths(STUDENT, [
      ['/courses/5', () => jsonResponse(200, COURSE)],
      [
        '/courses/5/assignments',
        () =>
          jsonResponse(
            200,
            page([assignment(11, 'Old', PAST), assignment(12, 'New')]),
          ),
      ],
      myGrades({
        11: jsonResponse(200, { assignmentId: 11, best: null }),
        12: jsonResponse(200, { assignmentId: 12, best: null }),
      }),
    ])
    renderAt('/courses/5')

    expect((await row(/Old/)).getByText(/\(closed\)/)).toBeInTheDocument()
    expect((await row(/New/)).queryByText(/\(closed\)/)).not.toBeInTheDocument()
  })

  it('a score that cannot be loaded is said in its own row only', async () => {
    stubPaths(STUDENT, [
      ['/courses/5', () => jsonResponse(200, COURSE)],
      [
        '/courses/5/assignments',
        () =>
          jsonResponse(
            200,
            page([assignment(11, 'Sum'), assignment(12, 'Sort')]),
          ),
      ],
      myGrades({
        11: () => {
          throw new TypeError('Failed to fetch')
        },
        12: jsonResponse(200, { assignmentId: 12, best: best(10, 10) }),
      }),
    ])
    renderAt('/courses/5')

    expect(
      await (await row(/Sum/)).findByText('Could not load the score'),
    ).toBeInTheDocument()
    expect(await (await row(/Sort/)).findByText('10 / 10')).toBeInTheDocument()
  })

  it('a course without assignments says so', async () => {
    stubPaths(STUDENT, [
      ['/courses/5', () => jsonResponse(200, COURSE)],
      ['/courses/5/assignments', () => jsonResponse(200, page([]))],
    ])
    renderAt('/courses/5')

    expect(
      await screen.findByText('There are no assignments in this course yet.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('navigation', { name: 'Pages' }),
    ).not.toBeInTheDocument()
  })

  it('pages through the assignments with the page in the URL', async () => {
    stubPaths(STUDENT, [
      ['/courses/5', () => jsonResponse(200, COURSE)],
      [
        '/courses/5/assignments',
        (url) => {
          const index = Number(url.searchParams.get('page'))
          return jsonResponse(
            200,
            page([assignment(20 + index, `Task ${index + 1}`)], index, 2, 21),
          )
        },
      ],
      myGrades({
        20: jsonResponse(200, { assignmentId: 20, best: null }),
        21: jsonResponse(200, { assignmentId: 21, best: null }),
      }),
    ])
    const user = renderAt('/courses/5')
    const pages = within(
      await screen.findByRole('navigation', { name: 'Pages' }),
    )

    await user.click(pages.getByRole('button', { name: 'Next' }))

    expect(
      await screen.findByRole('row', { name: /Task 2/ }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(
      '/courses/5?page=2',
    )
    expect(requested()).toContain('/courses/5/assignments?page=1')
  })
})

describe('the course page for the people who run it (9.2a)', () => {
  it('shows whether each assignment is published, and asks for no scores', async () => {
    stubPaths(TEACHER, [
      [
        '/courses/5',
        () => jsonResponse(200, { ...COURSE, enrollCode: 'ABCD2345' }),
      ],
      [
        '/courses/5/assignments',
        () =>
          jsonResponse(
            200,
            page([
              assignment(11, 'Sum', FUTURE, true),
              assignment(12, 'Draft one', FUTURE, false),
            ]),
          ),
      ],
    ])
    renderAt('/courses/5')

    expect((await row(/Sum/)).getByText('Published')).toBeInTheDocument()
    expect((await row(/Draft one/)).getByText('Draft')).toBeInTheDocument()
    expect(
      screen.getByRole('columnheader', { name: 'Status' }),
    ).toBeInTheDocument()
    expect(requested().filter((path) => path.includes('my-grade'))).toEqual([])
  })

  it('a teacher’s course without assignments says so', async () => {
    stubPaths(TEACHER, [
      ['/courses/5', () => jsonResponse(200, COURSE)],
      ['/courses/5/assignments', () => jsonResponse(200, page([]))],
    ])
    renderAt('/courses/5')

    expect(
      await screen.findByText('This course has no assignments yet.'),
    ).toBeInTheDocument()
  })
})

describe('a course that cannot be shown (9.2a)', () => {
  it('a course the user may not see, or that does not exist, is "not found", with a way back', async () => {
    stubPaths(STUDENT, [['/courses/5', notFound]])
    const user = renderAt('/courses/5')

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Course not found',
      }),
    ).toBeInTheDocument()
    expect(requested()).toEqual(['/courses/5'])

    stubPaths(STUDENT, [['/courses', () => jsonResponse(200, page([]))]])
    await user.click(
      within(screen.getByRole('main')).getByRole('link', {
        name: 'Back to your courses',
      }),
    )
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Courses' }),
    ).toBeInTheDocument()
  })

  it.each(['/courses/abc', '/courses/0', '/courses/1.5'])(
    '%s asks for nothing and is "not found"',
    async (path) => {
      stubPaths(STUDENT, [])
      renderAt(path)

      expect(
        await screen.findByRole('heading', {
          level: 1,
          name: 'Course not found',
        }),
      ).toBeInTheDocument()
      expect(rest).not.toHaveBeenCalled()
    },
  )

  it('a failed course load is shown, and no assignments are asked for', async () => {
    stubPaths(STUDENT, [
      ['/courses/5', () => Promise.reject(new TypeError('Failed to fetch'))],
    ])
    renderAt('/courses/5')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not reach the server',
    )
    expect(
      screen.getByRole('heading', { level: 1, name: 'Course' }),
    ).toBeInTheDocument()
    expect(requested()).toEqual(['/courses/5'])
  })

  it('a failed assignment list is shown under the course', async () => {
    stubPaths(STUDENT, [
      ['/courses/5', () => jsonResponse(200, COURSE)],
      [
        '/courses/5/assignments',
        () => Promise.reject(new TypeError('Failed to fetch')),
      ],
    ])
    renderAt('/courses/5')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not reach the server',
    )
    expect(
      screen.getByRole('heading', { level: 1, name: 'Algorithms' }),
    ).toBeInTheDocument()
  })
})

describe('getting to a course (9.2a)', () => {
  it('a student opens a course from their list', async () => {
    stubPaths(STUDENT, [
      ['/courses', () => jsonResponse(200, page([COURSE]))],
      ['/courses/5', () => jsonResponse(200, COURSE)],
      ['/courses/5/assignments', () => jsonResponse(200, page([]))],
    ])
    const user = renderAt('/courses')

    await user.click(await screen.findByRole('link', { name: 'Algorithms' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Algorithms' }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/courses\/5$/)
  })

  it('a teacher opens a course from their list', async () => {
    stubPaths(TEACHER, [
      [
        '/courses',
        () => jsonResponse(200, page([{ ...COURSE, enrollCode: 'ABCD2345' }])),
      ],
      ['/courses/5', () => jsonResponse(200, COURSE)],
      ['/courses/5/assignments', () => jsonResponse(200, page([]))],
    ])
    const user = renderAt('/courses')

    await user.click(await screen.findByRole('link', { name: 'Algorithms' }))

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Algorithms' }),
    ).toBeInTheDocument()
  })

  it('a signed-out visitor is sent to sign in, and nothing is asked for', async () => {
    stubPaths(null, [])
    stubApi(rest)
    renderAt('/courses/5')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Sign in' }),
    ).toBeInTheDocument()
    expect(rest).not.toHaveBeenCalled()
  })
})
