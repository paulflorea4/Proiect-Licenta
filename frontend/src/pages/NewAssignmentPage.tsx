import { Link, useNavigate, useParams } from 'react-router'
import { api } from '../api/client'
import type { Assignment, AssignmentBody } from '../api/types'
import AssignmentForm from '../components/AssignmentForm'

// A teacher creates an assignment in one of their courses (9.2b, POST /courses/{id}/assignments,
// teachers only, 3.3a). It is always created as a draft; the teacher is then taken to its edit page,
// where it can be published once its rubric and tests are ready (3.4b).
function NewAssignmentPage() {
  const raw = useParams().courseId
  const courseId = /^[1-9]\d*$/.test(raw ?? '') ? raw : null
  const navigate = useNavigate()

  async function create(body: AssignmentBody) {
    const created = await api.post<Assignment>(
      `/courses/${courseId}/assignments`,
      body,
    )
    navigate(`/assignments/${created.id}/edit`, { state: { created: true } })
  }

  if (courseId === null) {
    return (
      <main>
        <h1>Course not found</h1>
        <p>
          <Link to="/courses">Back to your courses</Link>
        </p>
      </main>
    )
  }

  return (
    <main>
      <h1>New assignment</h1>
      <p>
        <Link to={`/courses/${courseId}`}>Back to the course</Link>
      </p>
      <AssignmentForm
        submitLabel="Create assignment"
        submittingLabel="Creating assignment…"
        onSubmit={create}
      />
    </main>
  )
}

export default NewAssignmentPage
