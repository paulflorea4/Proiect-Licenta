import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router'
import { api, isApiError } from '../api/client'

// The limits of the backend's SignupRequest and PasswordPolicy, checked here only to answer
// sooner: the backend enforces them (and the email format) and its field errors are shown too.
const PASSWORD_MIN_LENGTH = 8
const PASSWORD_MAX_BYTES = 72
const TEXT_MAX_LENGTH = 255

type Field = 'fullName' | 'email' | 'password'
type FieldErrors = Partial<Record<Field, string>>

interface SignupBody {
  fullName: string
  email: string
  password: string
}

interface User {
  id: number
  email: string
  fullName: string
  role: string
}

const FIELDS: readonly Field[] = ['fullName', 'email', 'password']

function validate(body: SignupBody): FieldErrors {
  const errors: FieldErrors = {}
  if (body.fullName.trim() === '') {
    errors.fullName = 'Enter your full name'
  } else if (body.fullName.length > TEXT_MAX_LENGTH) {
    errors.fullName = `Use at most ${TEXT_MAX_LENGTH} characters`
  }
  if (body.email.trim() === '') {
    errors.email = 'Enter your email'
  } else if (body.email.trim().length > TEXT_MAX_LENGTH) {
    errors.email = `Use at most ${TEXT_MAX_LENGTH} characters`
  }
  if (body.password === '') {
    errors.password = 'Enter a password'
  } else if (body.password.length < PASSWORD_MIN_LENGTH) {
    errors.password = `Use at least ${PASSWORD_MIN_LENGTH} characters`
  } else if (
    new TextEncoder().encode(body.password).length > PASSWORD_MAX_BYTES
  ) {
    errors.password = 'This password is too long'
  }
  return errors
}

// Signup always creates a student: there is deliberately no role field (the backend ignores one).
function SignUpPage() {
  const [body, setBody] = useState<SignupBody>({
    fullName: '',
    email: '',
    password: '',
  })
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [created, setCreated] = useState<User | null>(null)

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
      setCreated(await api.post<User>('/auth/signup', body))
    } catch (error) {
      showError(error)
    } finally {
      setSubmitting(false)
    }
  }

  function showError(error: unknown) {
    if (isApiError(error, 'EMAIL_ALREADY_USED')) {
      setFieldErrors({ email: error.message })
    } else if (isApiError(error, 'VALIDATION_FAILED')) {
      const errors: FieldErrors = {}
      for (const { field, message } of error.fieldErrors) {
        if ((FIELDS as readonly string[]).includes(field)) {
          errors[field as Field] ??= message
        }
      }
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

  if (created) {
    return (
      <main>
        <h1>Sign up</h1>
        <p role="status">
          Your account for {created.email} is ready.{' '}
          <Link to="/signin">Sign in</Link>
        </p>
      </main>
    )
  }

  return (
    <main>
      <h1>Sign up</h1>
      <form onSubmit={submit} noValidate>
        <TextField
          field="fullName"
          label="Full name"
          autoComplete="name"
          value={body.fullName}
          error={fieldErrors.fullName}
          onChange={change}
        />
        <TextField
          field="email"
          label="Email"
          type="email"
          autoComplete="email"
          value={body.email}
          error={fieldErrors.email}
          onChange={change}
        />
        <TextField
          field="password"
          label="Password"
          type="password"
          autoComplete="new-password"
          value={body.password}
          error={fieldErrors.password}
          onChange={change}
        />
        {formError && <p role="alert">{formError}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Creating account…' : 'Create account'}
        </button>
      </form>
      <p>
        Already have an account? <Link to="/signin">Sign in</Link>
      </p>
    </main>
  )
}

interface TextFieldProps {
  field: Field
  label: string
  type?: 'text' | 'email' | 'password'
  autoComplete: string
  value: string
  error: string | undefined
  onChange: (field: Field, value: string) => void
}

function TextField({
  field,
  label,
  type = 'text',
  autoComplete,
  value,
  error,
  onChange,
}: TextFieldProps) {
  const id = `signup-${field}`
  return (
    <div>
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        name={field}
        type={type}
        autoComplete={autoComplete}
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        onChange={(event) => onChange(field, event.target.value)}
      />
      {error && <p id={`${id}-error`}>{error}</p>}
    </div>
  )
}

export default SignUpPage
