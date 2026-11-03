import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { API, jsonResponse, stubApi } from '../test/api'

// Every call but AuthProvider's GET /auth/me (signed out unless a test says otherwise).
const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  stubApi(fetchMock)
})

afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

const ADA = {
  id: 7,
  email: 'ana@example.com',
  fullName: 'Ana Pop',
  role: 'STUDENT',
}
const SIGNED_IN = { expiresIn: 86400, user: ADA }
const CREDENTIALS = { email: 'ana@example.com', password: 'correct horse' }

// Test-only: a way back to /signin from a page that has no link to it, inside the same router.
function BackToSignIn() {
  const navigate = useNavigate()
  return (
    <button type="button" onClick={() => navigate('/signin')}>
      test: back to sign-in
    </button>
  )
}

function renderSignIn() {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={['/signin']}>
      <App />
      <BackToSignIn />
    </MemoryRouter>,
  )
  return user
}

async function fill(
  user: ReturnType<typeof userEvent.setup>,
  values: { email?: string; password?: string },
) {
  if (values.email) {
    await user.type(screen.getByLabelText('Email'), values.email)
  }
  if (values.password) {
    await user.type(screen.getByLabelText('Password'), values.password)
  }
}

function submit(user: ReturnType<typeof userEvent.setup>) {
  return user.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('SignInPage', () => {
  it('has an email and a password field', () => {
    renderSignIn()

    expect(screen.getByLabelText('Email')).toHaveAttribute('type', 'email')
    expect(screen.getByLabelText('Password')).toHaveAttribute(
      'type',
      'password',
    )
    expect(screen.getByLabelText('Password')).toHaveAttribute(
      'autocomplete',
      'current-password',
    )
  })

  it('posts the credentials to /auth/signin, with the cookie and the CSRF header', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, SIGNED_IN))
    const user = renderSignIn()

    await fill(user, CREDENTIALS)
    await submit(user)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toBe(`${API}/auth/signin`)
    expect(init!.method).toBe('POST')
    expect(JSON.parse(String(init!.body))).toEqual(CREDENTIALS)
    expect(init!.credentials).toBe('include')
    expect((init!.headers as Record<string, string>)['X-Requested-With']).toBe(
      'XMLHttpRequest',
    )
  })

  it('a successful signin goes to the courses', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, SIGNED_IN))
    const user = renderSignIn()

    await fill(user, CREDENTIALS)
    await submit(user)

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Courses' }),
    ).toBeInTheDocument()
  })

  it('after signing in the app knows who is signed in, on any page', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, SIGNED_IN))
    const user = renderSignIn()
    await fill(user, CREDENTIALS)
    await submit(user)
    await screen.findByRole('heading', { level: 1, name: 'Courses' })

    await user.click(
      screen.getByRole('button', { name: 'test: back to sign-in' }),
    )

    expect(await screen.findByRole('status')).toHaveTextContent(
      'You are signed in as ana@example.com.',
    )
    // Recorded from the signin answer: no second GET /auth/me was needed.
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('someone already signed in is told so', async () => {
    stubApi(fetchMock, () => jsonResponse(200, ADA))

    renderSignIn()

    expect(await screen.findByRole('status')).toHaveTextContent(
      'You are signed in as ana@example.com.',
    )
    // The form stays: signing in as someone else is allowed.
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
  })

  it('an empty form is refused here, without a request', async () => {
    const user = renderSignIn()

    await submit(user)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'Enter your email',
    )
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      'Enter your password',
    )
  })

  it('wrong credentials are one message for the form; the password is cleared, the email kept', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(401, {
        status: 401,
        code: 'INVALID_CREDENTIALS',
        message: 'Invalid email or password',
      }),
    )
    const user = renderSignIn()

    await fill(user, CREDENTIALS)
    await submit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Invalid email or password',
    )
    expect(screen.getByLabelText('Password')).toHaveValue('')
    expect(screen.getByLabelText('Email')).toHaveValue('ana@example.com')
    // Still on the sign-in page.
    expect(
      screen.getByRole('heading', { level: 1, name: 'Sign in' }),
    ).toBeInTheDocument()
  })

  it('the backend’s field errors are shown on their fields', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, {
        status: 400,
        code: 'VALIDATION_FAILED',
        message: 'The request has invalid fields',
        fieldErrors: [{ field: 'password', message: 'is too long' }],
      }),
    )
    const user = renderSignIn()

    await fill(user, CREDENTIALS)
    await submit(user)

    expect(
      await screen.findByText('is too long', { selector: 'p' }),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      'is too long',
    )
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('a validation failure on no known field is shown for the form', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, {
        status: 400,
        code: 'VALIDATION_FAILED',
        message: 'The request has invalid fields',
        fieldErrors: [{ field: 'other', message: 'is wrong' }],
      }),
    )
    const user = renderSignIn()

    await fill(user, CREDENTIALS)
    await submit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The request has invalid fields',
    )
  })

  it('a network failure is shown, and a second try can succeed', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(jsonResponse(200, SIGNED_IN))
    const user = renderSignIn()

    await fill(user, CREDENTIALS)
    await submit(user)
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not reach the server',
    )

    await submit(user)

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Courses' }),
    ).toBeInTheDocument()
  })

  it('the button is disabled while the request runs, so it is sent once', async () => {
    let answer: (response: Response) => void = () => {}
    fetchMock.mockReturnValue(
      new Promise((resolve) => {
        answer = resolve
      }),
    )
    const user = renderSignIn()
    await fill(user, CREDENTIALS)

    await submit(user)

    const button = screen.getByRole('button', { name: 'Signing in…' })
    expect(button).toBeDisabled()
    await user.click(button)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    answer(jsonResponse(200, SIGNED_IN))
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Courses' }),
    ).toBeInTheDocument()
  })

  it('links to sign up', async () => {
    const user = renderSignIn()

    await user.click(screen.getByRole('link', { name: 'Sign up' }))

    expect(
      screen.getByRole('heading', { level: 1, name: 'Sign up' }),
    ).toBeInTheDocument()
  })
})
