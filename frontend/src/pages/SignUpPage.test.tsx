import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { stubApi } from '../test/api'

// Every call but AuthProvider's GET /auth/me (signed out here).
const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  stubApi(fetchMock)
})

afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const CREATED = {
  id: 12,
  email: 'ana@example.com',
  fullName: 'Ana Pop',
  role: 'STUDENT',
}

function renderSignUp() {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={['/signup']}>
      <App />
    </MemoryRouter>,
  )
  return user
}

async function fill(
  user: ReturnType<typeof userEvent.setup>,
  values: { fullName?: string; email?: string; password?: string },
) {
  if (values.fullName) {
    await user.type(screen.getByLabelText('Full name'), values.fullName)
  }
  if (values.email) {
    await user.type(screen.getByLabelText('Email'), values.email)
  }
  if (values.password) {
    await user.type(screen.getByLabelText('Password'), values.password)
  }
}

const VALID = {
  fullName: 'Ana Pop',
  email: 'ana@example.com',
  password: 'correct horse',
}

function submit(user: ReturnType<typeof userEvent.setup>) {
  return user.click(screen.getByRole('button', { name: 'Create account' }))
}

function sentBody(): unknown {
  const [, init] = fetchMock.mock.calls[0]
  return JSON.parse(String(init!.body))
}

describe('SignUpPage', () => {
  it('has the three fields and no way to choose a role', () => {
    renderSignUp()

    expect(screen.getByLabelText('Full name')).toHaveAttribute('type', 'text')
    expect(screen.getByLabelText('Email')).toHaveAttribute('type', 'email')
    expect(screen.getByLabelText('Password')).toHaveAttribute(
      'type',
      'password',
    )
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByText(/role|teacher|admin/i)).not.toBeInTheDocument()
  })

  it('posts the form to /auth/signup, without a role, and confirms the account', async () => {
    fetchMock.mockResolvedValue(json(201, CREATED))
    const user = renderSignUp()

    await fill(user, VALID)
    await submit(user)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('http://api.test/auth/signup')
    expect(init!.method).toBe('POST')
    expect(sentBody()).toEqual(VALID)
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Your account for ana@example.com is ready.',
    )
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument()
  })

  it('links to sign-in after the account is created', async () => {
    fetchMock.mockResolvedValue(json(201, CREATED))
    const user = renderSignUp()
    await fill(user, VALID)
    await submit(user)

    await user.click(
      await within(await screen.findByRole('status')).findByRole('link', {
        name: 'Sign in',
      }),
    )

    expect(
      screen.getByRole('heading', { level: 1, name: 'Sign in' }),
    ).toBeInTheDocument()
  })

  it('links to sign-in for someone who already has an account', async () => {
    const user = renderSignUp()

    await user.click(
      within(screen.getByRole('main')).getByRole('link', { name: 'Sign in' }),
    )

    expect(
      screen.getByRole('heading', { level: 1, name: 'Sign in' }),
    ).toBeInTheDocument()
  })

  it('an empty form is refused here, field by field, without a request', async () => {
    const user = renderSignUp()

    await submit(user)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Full name')).toHaveAccessibleDescription(
      'Enter your full name',
    )
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'Enter your email',
    )
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      'Enter a password',
    )
    expect(screen.getByLabelText('Password')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
  })

  it('a name or email of only spaces counts as empty', async () => {
    const user = renderSignUp()

    await fill(user, { ...VALID, fullName: '   ', email: '  ' })
    await submit(user)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Full name')).toHaveAccessibleDescription(
      'Enter your full name',
    )
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'Enter your email',
    )
  })

  it.each([
    ['7 characters', 'abcdefg', 'Use at least 8 characters'],
    ['73 bytes', 'a'.repeat(73), 'This password is too long'],
    [
      '37 two-byte characters (74 bytes)',
      'é'.repeat(37),
      'This password is too long',
    ],
  ])('a password of %s is refused here', async (_, password, message) => {
    const user = renderSignUp()

    await fill(user, { ...VALID, password })
    await submit(user)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      message,
    )
  })

  it.each([
    ['8 characters', 'abcdefgh'],
    ['72 bytes', 'a'.repeat(72)],
    ['36 two-byte characters (72 bytes)', 'é'.repeat(36)],
  ])('a password of %s is sent', async (_, password) => {
    fetchMock.mockResolvedValue(json(201, CREATED))
    const user = renderSignUp()

    await fill(user, { ...VALID, password })
    await submit(user)

    expect(sentBody()).toEqual({ ...VALID, password })
  })

  it('a name over 255 characters is refused here', async () => {
    const user = renderSignUp()

    // Pasted, not typed: typing 256 characters one by one is slow.
    await user.click(screen.getByLabelText('Full name'))
    await user.paste('a'.repeat(256))
    await fill(user, { email: VALID.email, password: VALID.password })
    await submit(user)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Full name')).toHaveAccessibleDescription(
      'Use at most 255 characters',
    )
  })

  // 8.6a: the boundaries and the email's own limit, which 8.3a's tests left out.
  it('a name of exactly 255 characters is sent', async () => {
    fetchMock.mockResolvedValue(json(201, CREATED))
    const user = renderSignUp()
    const fullName = 'a'.repeat(255)

    await user.click(screen.getByLabelText('Full name'))
    await user.paste(fullName)
    await fill(user, { email: VALID.email, password: VALID.password })
    await submit(user)

    expect(sentBody()).toEqual({ ...VALID, fullName })
  })

  it('an email over 255 characters is refused here', async () => {
    const user = renderSignUp()

    await fill(user, { fullName: VALID.fullName, password: VALID.password })
    await user.click(screen.getByLabelText('Email'))
    await user.paste(`${'a'.repeat(244)}@example.com`) // 256 characters
    await submit(user)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'Use at most 255 characters',
    )
  })

  it('an email of 255 characters with spaces around it is accepted and sent trimmed', async () => {
    fetchMock.mockResolvedValue(json(201, CREATED))
    const user = renderSignUp()
    const email = `  ${'a'.repeat(243)}@example.com  ` // 255 characters inside the spaces

    await fill(user, { fullName: VALID.fullName, password: VALID.password })
    await user.click(screen.getByLabelText('Email'))
    await user.paste(email)
    await submit(user)

    // A type="email" input strips surrounding spaces itself (the HTML value sanitization, as in a
    // browser); the backend trims and lower-cases again (8.0d). The 255 characters still pass.
    expect(sentBody()).toEqual({ ...VALID, email: email.trim() })
    expect(email.trim()).toHaveLength(255)
  })

  it('pressing Enter in a field submits the form', async () => {
    fetchMock.mockResolvedValue(json(201, CREATED))
    const user = renderSignUp()

    await fill(user, VALID)
    await user.type(screen.getByLabelText('Password'), '{Enter}')

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Your account for ana@example.com is ready.',
    )
    expect(sentBody()).toEqual(VALID)
  })

  it('pressing Enter on an incomplete form shows the errors, without a request', async () => {
    const user = renderSignUp()

    await user.type(screen.getByLabelText('Email'), 'ana@example.com{Enter}')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Full name')).toHaveAccessibleDescription(
      'Enter your full name',
    )
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      'Enter a password',
    )
  })

  it('an email already in use is shown on the email field', async () => {
    fetchMock.mockResolvedValue(
      json(409, {
        status: 409,
        code: 'EMAIL_ALREADY_USED',
        message: 'An account with this email already exists',
      }),
    )
    const user = renderSignUp()

    await fill(user, VALID)
    await submit(user)

    expect(
      await screen.findByText('An account with this email already exists'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'An account with this email already exists',
    )
    // The form stays, with what was typed.
    expect(screen.getByLabelText('Full name')).toHaveValue('Ana Pop')
  })

  it('the backend’s field errors are shown on their fields', async () => {
    fetchMock.mockResolvedValue(
      json(400, {
        status: 400,
        code: 'VALIDATION_FAILED',
        message: 'The request has invalid fields',
        fieldErrors: [
          { field: 'email', message: 'must be a well-formed email address' },
          { field: 'fullName', message: 'size must be between 0 and 255' },
        ],
      }),
    )
    const user = renderSignUp()

    await fill(user, { ...VALID, email: 'not-an-email' })
    await submit(user)

    expect(
      await screen.findByText('must be a well-formed email address'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'must be a well-formed email address',
    )
    expect(screen.getByLabelText('Full name')).toHaveAccessibleDescription(
      'size must be between 0 and 255',
    )
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('a validation failure on no known field is shown for the form', async () => {
    fetchMock.mockResolvedValue(
      json(400, {
        status: 400,
        code: 'VALIDATION_FAILED',
        message: 'The request has invalid fields',
        fieldErrors: [{ field: 'other', message: 'is wrong' }],
      }),
    )
    const user = renderSignUp()

    await fill(user, VALID)
    await submit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The request has invalid fields',
    )
    expect(screen.queryByText('is wrong')).not.toBeInTheDocument()
  })

  it.each([
    [
      'a server error',
      () =>
        json(500, {
          status: 500,
          code: 'INTERNAL_ERROR',
          message: 'An unexpected error occurred',
        }),
      'An unexpected error occurred',
    ],
    [
      'an unexpected response',
      () => new Response('<html>Bad Gateway</html>', { status: 502 }),
      'The server sent an unexpected response',
    ],
  ])('%s is shown for the form', async (_, response, message) => {
    fetchMock.mockResolvedValue(response())
    const user = renderSignUp()

    await fill(user, VALID)
    await submit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })

  it('a network failure is shown, and a second try can succeed', async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(json(201, CREATED))
    const user = renderSignUp()

    await fill(user, VALID)
    await submit(user)
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not reach the server',
    )

    await submit(user)

    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('the button is disabled while the request runs, so it is sent once', async () => {
    let answer: (response: Response) => void = () => {}
    fetchMock.mockReturnValue(
      new Promise((resolve) => {
        answer = resolve
      }),
    )
    const user = renderSignUp()
    await fill(user, VALID)

    await submit(user)

    const button = screen.getByRole('button', { name: 'Creating account…' })
    expect(button).toBeDisabled()
    await user.click(button)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    answer(json(201, CREATED))
    expect(await screen.findByRole('status')).toBeInTheDocument()
  })

  it('a new submit clears the previous errors', async () => {
    fetchMock.mockResolvedValue(
      json(500, {
        status: 500,
        code: 'INTERNAL_ERROR',
        message: 'An unexpected error occurred',
      }),
    )
    const user = renderSignUp()
    await submit(user)
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'Enter your email',
    )

    await fill(user, VALID)
    await submit(user)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).not.toHaveAccessibleDescription()
    expect(screen.getByLabelText('Email')).not.toHaveAttribute('aria-invalid')
  })
})
