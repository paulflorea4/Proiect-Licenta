import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { api, isApiError } from '../api/client'
import type { Course, PageResponse } from '../api/types'
import { useAuth } from '../auth/authContext'
import CopyButton from '../components/CopyButton'
import CreateCourseForm from '../components/CreateCourseForm'
import Pager from '../components/Pager'
import { pageNumberOf } from '../components/pagination'

// The last answer and the page it is for; it is "loading" while that is not the page in the URL
// (state is only set from the request's callbacks, never in the effect itself; 8.5b).
type Result =
  | { forPage: number; status: 'failed'; message: string }
  | { forPage: number; status: 'loaded'; page: PageResponse<Course> }

type Load = { status: 'loading' } | Result

function messageOf(error: unknown): string {
  return isApiError(error)
    ? error.message
    : 'Something went wrong. Please try again.'
}

/** An enrollment code shown large, with a copy action (9.1a: it is what students need from here). */
function EnrollCode({ course }: { course: Course }) {
  if (!course.enrollCode) {
    return null
  }
  return (
    <span className="enroll-code">
      <code>{course.enrollCode}</code>{' '}
      <CopyButton
        text={course.enrollCode}
        label={`Copy the enrollment code of ${course.title}`}
      />
    </span>
  )
}

// The courses of the people who run them (9.1a): a teacher's own, or every course for an admin
// (GET /courses picks the list by the caller's role, 3.1b), each with its enrollment code; a
// teacher also gets the create form (POST /courses is teachers only, 3.1a).
function ManagedCourses({ canCreate }: { canCreate: boolean }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const pageNumber = pageNumberOf(searchParams.get('page'))
  const [reloads, setReloads] = useState(0)
  const [result, setResult] = useState<Result | null>(null)
  const load: Load =
    result && result.forPage === pageNumber ? result : { status: 'loading' }
  const [created, setCreated] = useState<Course | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    api
      .get<PageResponse<Course>>(`/courses?page=${pageNumber - 1}`, {
        signal: controller.signal,
      })
      .then((page) =>
        setResult({ forPage: pageNumber, status: 'loaded', page }),
      )
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setResult({
            forPage: pageNumber,
            status: 'failed',
            message: messageOf(error),
          })
        }
      })
    return () => controller.abort()
  }, [pageNumber, reloads])

  function goTo(n: number) {
    setSearchParams(n === 1 ? {} : { page: String(n) })
  }

  function courseCreated(course: Course) {
    setCreated(course)
    // The list is oldest first, so the new course is on the last page; reload the one shown so
    // its count and page total are current.
    setReloads((n) => n + 1)
  }

  return (
    <>
      {created && (
        <section aria-label="New course">
          <p role="status">
            “{created.title}” is created. Students join it with this enrollment
            code:
          </p>
          <p>
            <EnrollCode course={created} />
          </p>
        </section>
      )}
      {load.status === 'loading' && <p role="status">Loading courses…</p>}
      {load.status === 'failed' && <p role="alert">{load.message}</p>}
      {load.status === 'loaded' && (
        <>
          {load.page.totalElements === 0 ? (
            <p>
              {canCreate
                ? 'You have no courses yet. Create your first one below.'
                : 'There are no courses yet.'}
            </p>
          ) : load.page.items.length === 0 ? (
            <p>No courses on this page.</p>
          ) : (
            <table>
              <caption>
                {canCreate ? 'Your courses' : 'All courses'}, oldest first
              </caption>
              <thead>
                <tr>
                  <th scope="col">Course</th>
                  <th scope="col">Enrollment code</th>
                </tr>
              </thead>
              <tbody>
                {load.page.items.map((course) => (
                  <tr key={course.id}>
                    <td>{course.title}</td>
                    <td>
                      <EnrollCode course={course} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {load.page.totalElements > 0 && (
            <Pager
              pageNumber={pageNumber}
              totalPages={load.page.totalPages}
              totalElements={load.page.totalElements}
              noun={['course', 'courses']}
              onGoTo={goTo}
            />
          )}
        </>
      )}
      {canCreate && <CreateCourseForm onCreated={courseCreated} />}
    </>
  )
}

// The home of students and teachers (8.4a), behind ProtectedRoute (8.3c). Teachers and admins see
// the courses they run (9.1a); the student's own list and join form come with 9.1b.
function CoursesPage() {
  const auth = useAuth()
  const role = auth.state.status === 'signedIn' ? auth.state.user.role : null
  return (
    <main>
      <h1>Courses</h1>
      {role === 'TEACHER' && <ManagedCourses canCreate />}
      {role === 'ADMIN' && <ManagedCourses canCreate={false} />}
    </main>
  )
}

export default CoursesPage
