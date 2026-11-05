import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { api, isApiError } from '../api/client'
import type { Course, PageResponse } from '../api/types'
import { useAuth } from '../auth/authContext'
import CopyButton from '../components/CopyButton'
import CreateCourseForm from '../components/CreateCourseForm'
import JoinCourseForm from '../components/JoinCourseForm'
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

// One page of GET /courses, which picks the list by the caller's role (3.1b): a teacher's own
// courses, a student's enrolled ones, every course for an admin. The page is in the URL (8.5b);
// `reload` asks the page shown again, keeping the old answer on screen until the new one arrives.
function useCourseList() {
  const [searchParams, setSearchParams] = useSearchParams()
  const pageNumber = pageNumberOf(searchParams.get('page'))
  const [reloads, setReloads] = useState(0)
  const [result, setResult] = useState<Result | null>(null)
  const load: Load =
    result && result.forPage === pageNumber ? result : { status: 'loading' }

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

  function reload() {
    setReloads((n) => n + 1)
  }

  return { pageNumber, load, goTo, reload }
}

// The courses of the people who run them (9.1a): a teacher's own, or every course for an admin,
// each with its enrollment code; a teacher also gets the create form (POST /courses is teachers
// only, 3.1a).
function ManagedCourses({ canCreate }: { canCreate: boolean }) {
  const { pageNumber, load, goTo, reload } = useCourseList()
  const [created, setCreated] = useState<Course | null>(null)

  function courseCreated(course: Course) {
    setCreated(course)
    // The list is oldest first, so the new course is on the last page; reload the one shown so
    // its count and page total are current.
    reload()
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

// A student's courses (9.1b): the join form first, then the courses they are in (no codes: a
// student's course has none, 3.1b). A student with no course is told how to join, so a new
// account's first page explains itself instead of looking empty.
function StudentCourses() {
  const { pageNumber, load, goTo, reload } = useCourseList()
  const [joined, setJoined] = useState<Course | null>(null)

  function courseJoined(course: Course) {
    setJoined(course)
    reload()
  }

  return (
    <>
      <JoinCourseForm onJoined={courseJoined} />
      {joined && <p role="status">You are in “{joined.title}”.</p>}
      <h2>My courses</h2>
      {load.status === 'loading' && <p role="status">Loading courses…</p>}
      {load.status === 'failed' && <p role="alert">{load.message}</p>}
      {load.status === 'loaded' && (
        <>
          {load.page.totalElements === 0 ? (
            <p>
              You are not in any course yet. Ask your teacher for the course’s
              enrollment code and enter it above to join.
            </p>
          ) : load.page.items.length === 0 ? (
            <p>No courses on this page.</p>
          ) : (
            <ul aria-label="My courses">
              {load.page.items.map((course) => (
                <li key={course.id}>
                  {course.title}
                  {course.description && <p>{course.description}</p>}
                </li>
              ))}
            </ul>
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
    </>
  )
}

// The home of students and teachers (8.4a), behind ProtectedRoute (8.3c). Teachers and admins see
// the courses they run (9.1a), students the courses they are in and a join form (9.1b).
function CoursesPage() {
  const auth = useAuth()
  const role = auth.state.status === 'signedIn' ? auth.state.user.role : null
  return (
    <main>
      <h1>Courses</h1>
      {role === 'TEACHER' && <ManagedCourses canCreate />}
      {role === 'ADMIN' && <ManagedCourses canCreate={false} />}
      {role === 'STUDENT' && <StudentCourses />}
    </main>
  )
}

export default CoursesPage
