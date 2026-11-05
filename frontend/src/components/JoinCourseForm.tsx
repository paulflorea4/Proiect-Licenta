import { useState } from 'react'
import type { FormEvent } from 'react'
import { api, isApiError } from '../api/client'
import type { Course } from '../api/types'
import TextField from './TextField'
import { fieldErrorsOf } from './fieldErrors'

// The backend's EnrollRequest limit (3.2a), checked here only to answer sooner. The backend trims
// and upper-cases what is typed, so case and surrounding spaces do not matter.
const CODE_MAX_LENGTH = 20

type Field = 'code'
const FIELDS: readonly Field[] = ['code']

interface JoinCourseFormProps {
  /** The course as `POST /courses/enroll` answered it (a student's shape: no code). */
  onJoined: (course: Course) => void
}

// A student's "join a course" form (9.1b): POST /courses/enroll with the code the teacher gave.
// Joining a course the student is already in is the same 200 with the same course (3.2a).
function JoinCourseForm({ onJoined }: JoinCourseFormProps) {
  const [code, setCode] = useState('')
  const [codeError, setCodeError] = useState<string | undefined>()
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) {
      return
    }
    const typed = code.trim()
    setFormError(null)
    if (typed === '') {
      setCodeError('Enter the enrollment code your teacher gave you')
      return
    }
    if (typed.length > CODE_MAX_LENGTH) {
      setCodeError(
        `An enrollment code has at most ${CODE_MAX_LENGTH} characters`,
      )
      return
    }
    setCodeError(undefined)
    setSubmitting(true)
    try {
      const course = await api.post<Course>('/courses/enroll', { code: typed })
      setCode('')
      onJoined(course)
    } catch (error) {
      showError(error)
    } finally {
      setSubmitting(false)
    }
  }

  function showError(error: unknown) {
    if (isApiError(error, 'ENROLL_CODE_NOT_FOUND')) {
      setCodeError(error.message)
    } else if (isApiError(error, 'VALIDATION_FAILED')) {
      const errors = fieldErrorsOf(error, FIELDS)
      if (errors.code) {
        setCodeError(errors.code)
      } else {
        setFormError(error.message)
      }
    } else if (isApiError(error)) {
      setFormError(error.message)
    } else {
      setFormError('Something went wrong. Please try again.')
    }
  }

  return (
    <form onSubmit={submit} noValidate aria-labelledby="join-course-heading">
      <h2 id="join-course-heading">Join a course</h2>
      <TextField
        form="join"
        field="code"
        label="Enrollment code"
        autoComplete="off"
        value={code}
        error={codeError}
        onChange={(_, value) => setCode(value)}
      />
      {formError && <p role="alert">{formError}</p>}
      <button type="submit" disabled={submitting}>
        {submitting ? 'Joining…' : 'Join course'}
      </button>
    </form>
  )
}

export default JoinCourseForm
