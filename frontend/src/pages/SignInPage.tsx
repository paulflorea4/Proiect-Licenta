import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { api, isApiError } from '../api/client'
import { returnPathOf, useAuth } from '../auth/authContext'
import type { User } from '../auth/authContext'
import TextField from '../components/TextField'
import { fieldErrorsOf } from '../components/fieldErrors'

type Field = 'email' | 'password'
type FieldErrors = Partial<Record<Field, string>>

interface SigninBody {
  email: string
  password: string
}

/** `SigninResponse`: the token is not in it, the backend sets it as an `httpOnly` cookie (8.2b). */
interface SigninResponse {
  expiresIn: number
  user: User
}

const FIELDS: readonly Field[] = ['email', 'password']

/** Where a successful signin goes, unless a guarded route sent the user here (8.3c). */
const AFTER_SIGNIN = '/courses'

function validate(body: SigninBody): FieldErrors {
  const errors: FieldErrors = {}
  if (body.email.trim() === '') {
    errors.email = 'Enter your email'
  }
  if (body.password === '') {
    errors.password = 'Enter your password'
  }
  return errors
}

// Signs in with POST /auth/signin. The JWT arrives as an httpOnly cookie the page never sees; the
// page only records the returned user (useAuth) and moves on to the courses.
function SignInPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [body, setBody] = useState<SigninBody>({ email: '', password: '' })
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
      const response = await api.post<SigninResponse>('/auth/signin', body)
      auth.signedIn(response.user)
      navigate(returnPathOf(location.state) ?? AFTER_SIGNIN, { replace: true })
    } catch (error) {
      setSubmitting(false)
      showError(error)
    }
  }

  function showError(error: unknown) {
    if (isApiError(error, 'INVALID_CREDENTIALS')) {
      // Wrong password and unknown email are one answer (2.4a); the password is typed again.
      setBody((current) => ({ ...current, password: '' }))
      setFormError(error.message)
    } else if (isApiError(error, 'VALIDATION_FAILED')) {
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
    <main>
      <h1>Sign in</h1>
      {auth.state.status === 'signedIn' && (
        <p role="status">
          You are signed in as {auth.state.user.email}.{' '}
          <Link to={AFTER_SIGNIN}>Go to your courses</Link>
        </p>
      )}
      <form onSubmit={submit} noValidate>
        <TextField
          form="signin"
          field="email"
          label="Email"
          type="email"
          autoComplete="email"
          value={body.email}
          error={fieldErrors.email}
          onChange={change}
        />
        <TextField
          form="signin"
          field="password"
          label="Password"
          type="password"
          autoComplete="current-password"
          value={body.password}
          error={fieldErrors.password}
          onChange={change}
        />
        {formError && <p role="alert">{formError}</p>}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <p>
        No account yet? <Link to="/signup">Sign up</Link>
      </p>
    </main>
  )
}

export default SignInPage
