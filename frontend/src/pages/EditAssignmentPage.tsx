import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { api, isApiError } from '../api/client'
import type { Assignment, AssignmentBody } from '../api/types'
import AssignmentForm from '../components/AssignmentForm'

function messageOf(error: unknown): string {
  return isApiError(error)
    ? error.message
    : 'Something went wrong. Please try again.'
}

type Load =
  | { forId: string; status: 'loaded'; assignment: Assignment }
  | { forId: string; status: 'missing' }
  | { forId: string; status: 'failed'; message: string }

// Publishing (3.3c, 3.4b, 9.0e): a draft is published once its rubric and tests are complete and
// its deadline is ahead; the backend checks it and its refusal is shown here. Hiding never checks.
function Publishing({
  assignment,
  onChanged,
}: {
  assignment: Assignment
  onChanged: (assignment: Assignment) => void
}) {
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function toggle() {
    setSending(true)
    setError(null)
    try {
      onChanged(
        await api.post<Assignment>(
          `/assignments/${assignment.id}/${assignment.published ? 'unpublish' : 'publish'}`,
        ),
      )
    } catch (e) {
      setError(messageOf(e))
    } finally {
      setSending(false)
    }
  }

  return (
    <section aria-labelledby="publishing-heading">
      <h2 id="publishing-heading">Publishing</h2>
      <p>
        {assignment.published
          ? 'Published: students in the course can see it and submit.'
          : 'Draft: students cannot see it.'}
      </p>
      {error && <p role="alert">{error}</p>}
      <button type="button" disabled={sending} onClick={() => void toggle()}>
        {assignment.published ? 'Unpublish' : 'Publish'}
      </button>
    </section>
  )
}

// A teacher or admin changes an assignment (9.2b, PUT /assignments/{id}, 3.3c) and publishes or
// hides it. What the backend refuses (a lock after the first submission, 3.3c and 9.0b, or a change
// to a published assignment's language or limits, 9.0c) is shown on the form.
function EditAssignmentPage() {
  const raw = useParams().assignmentId
  const assignmentId = /^[1-9]\d*$/.test(raw ?? '') ? raw! : null
  const location = useLocation()
  const justCreated =
    (location.state as { created?: boolean } | null)?.created === true
  const [result, setResult] = useState<Load | null>(null)
  const load = result && result.forId === assignmentId ? result : null
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    if (assignmentId === null) {
      return
    }
    const controller = new AbortController()
    api
      .get<Assignment>(`/assignments/${assignmentId}`, {
        signal: controller.signal,
      })
      .then((assignment) =>
        setResult({ forId: assignmentId, status: 'loaded', assignment }),
      )
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return
        }
        setResult(
          isApiError(error, 'ASSIGNMENT_NOT_FOUND')
            ? { forId: assignmentId, status: 'missing' }
            : {
                forId: assignmentId,
                status: 'failed',
                message: messageOf(error),
              },
        )
      })
    return () => controller.abort()
  }, [assignmentId])

  function replace(assignment: Assignment) {
    setResult({ forId: String(assignment.id), status: 'loaded', assignment })
  }

  async function save(body: AssignmentBody) {
    if (load?.status !== 'loaded') {
      return
    }
    setSaved(false)
    replace(
      await api.put<Assignment>(`/assignments/${load.assignment.id}`, body),
    )
    setSaved(true)
  }

  if (assignmentId === null || load?.status === 'missing') {
    return (
      <main>
        <h1>Assignment not found</h1>
        <p>
          This assignment does not exist, or you cannot change it.{' '}
          <Link to="/courses">Back to your courses</Link>
        </p>
      </main>
    )
  }

  return (
    <main>
      <h1>
        {load?.status === 'loaded' ? load.assignment.title : 'Assignment'}
      </h1>
      {load === null && <p role="status">Loading the assignment…</p>}
      {load?.status === 'failed' && <p role="alert">{load.message}</p>}
      {load?.status === 'loaded' && (
        <>
          <p>
            <Link to={`/courses/${load.assignment.courseId}`}>
              Back to the course
            </Link>
          </p>
          {justCreated && (
            <p role="status">
              The assignment is created as a draft: students cannot see it until
              you publish it.
            </p>
          )}
          {saved && <p role="status">Saved.</p>}
          <AssignmentForm
            original={load.assignment}
            gradingLocked={load.assignment.published === true}
            submitLabel="Save changes"
            submittingLabel="Saving…"
            onSubmit={save}
          />
          <Publishing assignment={load.assignment} onChanged={replace} />
        </>
      )}
    </main>
  )
}

export default EditAssignmentPage
