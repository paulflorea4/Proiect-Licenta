import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { api, isApiError } from '../api/client'
import type { Assignment, Course, MyGrade, PageResponse } from '../api/types'
import { useAuth } from '../auth/authContext'
import Pager from '../components/Pager'
import { formatDateTime, formatScore } from '../components/format'
import { pageNumberOf } from '../components/pagination'

function messageOf(error: unknown): string {
  return isApiError(error)
    ? error.message
    : 'Something went wrong. Please try again.'
}

/** A course id from the URL: a whole number from 1, or null (nothing is asked for then). */
function courseIdOf(value: string | undefined): number | null {
  const n = Number(value)
  return Number.isInteger(n) && n >= 1 ? n : null
}

// Each answer records what it is for, so a stale one is never shown (8.5b): "loading" is derived
// when the last answer is for another course or page.
type CourseLoad =
  | { forCourse: number; status: 'loaded'; course: Course }
  | { forCourse: number; status: 'missing' }
  | { forCourse: number; status: 'failed'; message: string }

type ListLoad =
  | { forKey: string; status: 'loaded'; page: PageResponse<Assignment> }
  | { forKey: string; status: 'failed'; message: string }

type GradeLoad =
  { status: 'loaded'; grade: MyGrade } | { status: 'failed'; message: string }

/** When the deadline has passed, say so next to it (submissions are refused after it, 5.1c). */
function Deadline({ iso }: { iso: string }) {
  const [now] = useState(() => Date.now())
  return (
    <>
      <time dateTime={iso}>{formatDateTime(iso)}</time>
      {new Date(iso).getTime() < now && ' (closed)'}
    </>
  )
}

// A student's best score for one assignment (5.4c, GET /assignments/{id}/my-grade): the attempt
// whose score counts, or that none is graded yet. A provisional score still waits for a teacher's
// mark on some criterion (5.4e).
function BestScore({ assignmentId }: { assignmentId: number }) {
  const [load, setLoad] = useState<GradeLoad | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    api
      .get<MyGrade>(`/assignments/${assignmentId}/my-grade`, {
        signal: controller.signal,
      })
      .then((grade) => setLoad({ status: 'loaded', grade }))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setLoad({ status: 'failed', message: messageOf(error) })
        }
      })
    return () => controller.abort()
  }, [assignmentId])

  if (load === null) {
    return <span>Loading…</span>
  }
  if (load.status === 'failed') {
    return <span>Could not load the score</span>
  }
  const best = load.grade.best
  if (best === null) {
    return <span>Not graded yet</span>
  }
  return (
    <span>
      {formatScore(best.totalScore)} / {formatScore(best.maxScore)}
      {best.provisional && ' (provisional)'}
    </span>
  )
}

// The assignments of a course (9.2a): GET /courses/{id}/assignments, which shows a student the
// published ones only (3.3b), with each deadline; a student also sees their best score, the people
// who run the course whether each is published.
function AssignmentList({
  courseId,
  isStudent,
}: {
  courseId: number
  isStudent: boolean
}) {
  const [searchParams, setSearchParams] = useSearchParams()
  const pageNumber = pageNumberOf(searchParams.get('page'))
  const key = `${courseId}:${pageNumber}`
  const [result, setResult] = useState<ListLoad | null>(null)
  const load = result && result.forKey === key ? result : null

  useEffect(() => {
    const controller = new AbortController()
    const forKey = `${courseId}:${pageNumber}`
    api
      .get<PageResponse<Assignment>>(
        `/courses/${courseId}/assignments?page=${pageNumber - 1}`,
        { signal: controller.signal },
      )
      .then((page) => setResult({ forKey, status: 'loaded', page }))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setResult({ forKey, status: 'failed', message: messageOf(error) })
        }
      })
    return () => controller.abort()
  }, [courseId, pageNumber])

  function goTo(n: number) {
    setSearchParams(n === 1 ? {} : { page: String(n) })
  }

  return (
    <section aria-labelledby="assignments-heading">
      <h2 id="assignments-heading">Assignments</h2>
      {load === null && <p role="status">Loading assignments…</p>}
      {load?.status === 'failed' && <p role="alert">{load.message}</p>}
      {load?.status === 'loaded' && (
        <>
          {load.page.totalElements === 0 ? (
            <p>
              {isStudent
                ? 'There are no assignments in this course yet.'
                : 'This course has no assignments yet.'}
            </p>
          ) : load.page.items.length === 0 ? (
            <p>No assignments on this page.</p>
          ) : (
            <table>
              <caption>Assignments, oldest first</caption>
              <thead>
                <tr>
                  <th scope="col">Assignment</th>
                  <th scope="col">Deadline</th>
                  <th scope="col">{isStudent ? 'Best score' : 'Status'}</th>
                </tr>
              </thead>
              <tbody>
                {load.page.items.map((assignment) => (
                  <tr key={assignment.id}>
                    <td>{assignment.title}</td>
                    <td>
                      <Deadline iso={assignment.deadline} />
                    </td>
                    <td>
                      {isStudent ? (
                        <BestScore assignmentId={assignment.id} />
                      ) : assignment.published ? (
                        'Published'
                      ) : (
                        'Draft'
                      )}
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
              noun={['assignment', 'assignments']}
              onGoTo={goTo}
            />
          )}
        </>
      )}
    </section>
  )
}

// One course (9.2a), behind ProtectedRoute: its title and description, then its assignments. A
// course the user may not see is the backend's 404 (3.1c), the same as one that does not exist,
// and the page says so the same way.
function CoursePage() {
  const auth = useAuth()
  const isStudent =
    auth.state.status === 'signedIn' && auth.state.user.role === 'STUDENT'
  const courseId = courseIdOf(useParams().courseId)
  const [result, setResult] = useState<CourseLoad | null>(null)
  const load = result && result.forCourse === courseId ? result : null

  useEffect(() => {
    if (courseId === null) {
      return
    }
    const controller = new AbortController()
    api
      .get<Course>(`/courses/${courseId}`, { signal: controller.signal })
      .then((course) =>
        setResult({ forCourse: courseId, status: 'loaded', course }),
      )
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return
        }
        setResult(
          isApiError(error, 'COURSE_NOT_FOUND')
            ? { forCourse: courseId, status: 'missing' }
            : {
                forCourse: courseId,
                status: 'failed',
                message: messageOf(error),
              },
        )
      })
    return () => controller.abort()
  }, [courseId])

  if (courseId === null || load?.status === 'missing') {
    return (
      <main>
        <h1>Course not found</h1>
        <p>
          This course does not exist, or you are not in it.{' '}
          <Link to="/courses">Back to your courses</Link>
        </p>
      </main>
    )
  }

  return (
    <main>
      <h1>{load?.status === 'loaded' ? load.course.title : 'Course'}</h1>
      <p>
        <Link to="/courses">All courses</Link>
      </p>
      {load === null && <p role="status">Loading the course…</p>}
      {load?.status === 'failed' && <p role="alert">{load.message}</p>}
      {load?.status === 'loaded' && (
        <>
          {load.course.description && <p>{load.course.description}</p>}
          <AssignmentList courseId={courseId} isStudent={isStudent} />
        </>
      )}
    </main>
  )
}

export default CoursePage
