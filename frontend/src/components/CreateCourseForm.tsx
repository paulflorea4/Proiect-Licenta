import { useState } from 'react'
import type { FormEvent } from 'react'
import { api, isApiError } from '../api/client'
import type { Course } from '../api/types'
import TextField from './TextField'
import { fieldErrorsOf } from './fieldErrors'

// The limits of the backend's CourseRequest (3.1a), checked here only to answer sooner: the
// backend enforces them and its field errors are shown the same way. It trims the title and treats
// a blank description as none.
const TITLE_MAX_LENGTH = 255
const DESCRIPTION_MAX_LENGTH = 5000

type Field = 'title' | 'description'
type FieldErrors = Partial<Record<Field, string>>

interface CourseBody {
  title: string
  description: string
}

const FIELDS: readonly Field[] = ['title', 'description']
const EMPTY: CourseBody = { title: '', description: '' }

function validate(body: CourseBody): FieldErrors {
  const errors: FieldErrors = {}
  const title = body.title.trim()
  if (title === '') {
    errors.title = 'Enter a title'
  } else if (title.length > TITLE_MAX_LENGTH) {
    errors.title = `Use at most ${TITLE_MAX_LENGTH} characters`
  }
  if (body.description.length > DESCRIPTION_MAX_LENGTH) {
    errors.description = `Use at most ${DESCRIPTION_MAX_LENGTH} characters`
  }
  return errors
}

interface CreateCourseFormProps {
  /** The new course as `POST /courses` answered it, enrollment code included. */
  onCreated: (course: Course) => void
}

// A teacher's "create a course" form (9.1a): POST /courses, teachers only (3.1a). On success the
// form empties and hands the new course up; the page shows its enrollment code.
function CreateCourseForm({ onCreated }: CreateCourseFormProps) {
  const [body, setBody] = useState<CourseBody>(EMPTY)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function change(field: Field, value: string) {
    setBody((current) => ({ ...current, [field]: value }))
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) {
      return
    }
    const errors = validate(body)
    setFieldErrors(errors)
    setFormError(null)
    if (Object.keys(errors).length > 0) {
      return
    }
    setSubmitting(true)
    try {
      const course = await api.post<Course>('/courses', {
        title: body.title.trim(),
        description: body.description.trim() === '' ? null : body.description,
      })
      setBody(EMPTY)
      onCreated(course)
    } catch (error) {
      showError(error)
    } finally {
      setSubmitting(false)
    }
  }

  function showError(error: unknown) {
    if (isApiError(error, 'VALIDATION_FAILED')) {
      const errors = fieldErrorsOf(error, FIELDS)
      setFieldErrors(errors)
      if (Object.keys(errors).length === 0) {
        setFormError(error.message)
      }
    } else if (isApiError(error)) {
      setFormError(error.message)
    } else {
      setFormError('Something went wrong. Please try again.')
    }
  }

  return (
    <form onSubmit={submit} noValidate aria-labelledby="create-course-heading">
      <h2 id="create-course-heading">Create a course</h2>
      <TextField
        form="course"
        field="title"
        label="Title"
        autoComplete="off"
        value={body.title}
        error={fieldErrors.title}
        onChange={change}
      />
      <TextField
        form="course"
        field="description"
        label="Description (optional)"
        rows={4}
        autoComplete="off"
        value={body.description}
        error={fieldErrors.description}
        onChange={change}
      />
      {formError && <p role="alert">{formError}</p>}
      <button type="submit" disabled={submitting}>
        {submitting ? 'Creating course…' : 'Create course'}
      </button>
    </form>
  )
}

export default CreateCourseForm
