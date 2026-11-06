import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { api, isApiError } from '../api/client'
import type { Assignment, AssignmentBody, Language } from '../api/types'
import TextField from './TextField'
import { fieldErrorsOf } from './fieldErrors'
import { fromLocalInput, toLocalInput } from './format'

// The limits of the backend's AssignmentRequest (3.3a), checked here only to answer sooner: the
// backend enforces them and its errors are shown on the same fields.
const TITLE_MAX_LENGTH = 255
const DESCRIPTION_MAX_LENGTH = 20_000
const STARTER_CODE_MAX_LENGTH = 50_000
const MAX_ATTEMPTS_MAX = 100
const TIME_LIMIT_MAX_MS = 10_000
const MEMORY_LIMIT_MAX_MB = 1024

type Field =
  | 'title'
  | 'description'
  | 'language'
  | 'deadline'
  | 'maxAttempts'
  | 'timeLimitMs'
  | 'memoryLimitMb'
  | 'starterCode'

type Values = Record<Field, string>
type FieldErrors = Partial<Record<Field, string>>

const FIELDS: readonly Field[] = [
  'title',
  'description',
  'language',
  'deadline',
  'maxAttempts',
  'timeLimitMs',
  'memoryLimitMb',
  'starterCode',
]

// An error code that is about one field goes on it (8.3a); a limits lock is about both limits.
const CODE_FIELDS: Record<string, readonly Field[]> = {
  UNSUPPORTED_LANGUAGE: ['language'],
  ASSIGNMENT_LANGUAGE_LOCKED: ['language'],
  MEMORY_LIMIT_BELOW_LANGUAGE_MINIMUM: ['memoryLimitMb'],
  ASSIGNMENT_LIMITS_LOCKED: ['timeLimitMs', 'memoryLimitMb'],
  DEADLINE_NOT_IN_FUTURE: ['deadline'],
  DEADLINE_TOO_FAR: ['deadline'],
}

function valuesOf(assignment: Assignment | undefined): Values {
  return {
    title: assignment?.title ?? '',
    description: assignment?.description ?? '',
    language: assignment?.language ?? '',
    deadline: assignment ? toLocalInput(assignment.deadline) : '',
    maxAttempts: assignment?.maxAttempts?.toString() ?? '',
    timeLimitMs: assignment?.timeLimitMs.toString() ?? '',
    memoryLimitMb: assignment?.memoryLimitMb.toString() ?? '',
    starterCode: assignment?.starterCode ?? '',
  }
}

/** A whole number in [min, max], or null. */
function wholeNumber(value: string, min: number, max: number): number | null {
  if (!/^\d+$/.test(value.trim())) {
    return null
  }
  const n = Number(value.trim())
  return n >= min && n <= max ? n : null
}

interface AssignmentFormProps {
  /** The assignment being changed, or none to create one. */
  original?: Assignment
  /**
   * A published assignment's language and limits cannot change (9.0c): they are shown but not
   * editable, and sent as they are.
   */
  gradingLocked?: boolean
  submitLabel: string
  submittingLabel: string
  /** Sends the body; its `ApiError` is shown on the form. */
  onSubmit: (body: AssignmentBody) => Promise<void>
}

// The teacher's assignment form (9.2b), for creating and for changing one: everything of 3.3a's
// AssignmentRequest. The languages come from GET /languages (9.2b), so none is named here and a new
// one needs only the backend's config (4.1b).
function AssignmentForm({
  original,
  gradingLocked = false,
  submitLabel,
  submittingLabel,
  onSubmit,
}: AssignmentFormProps) {
  const [values, setValues] = useState<Values>(() => valuesOf(original))
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [languages, setLanguages] = useState<
    | { status: 'loaded'; list: Language[] }
    | { status: 'failed'; message: string }
    | null
  >(null)

  useEffect(() => {
    const controller = new AbortController()
    api
      .get<{ languages: Language[] }>('/languages', {
        signal: controller.signal,
      })
      .then(({ languages: list }) => setLanguages({ status: 'loaded', list }))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setLanguages({
            status: 'failed',
            message: isApiError(error)
              ? error.message
              : 'Something went wrong. Please try again.',
          })
        }
      })
    return () => controller.abort()
  }, [])

  // A language no longer configured stays selectable on its own assignment, so the form can send
  // it back unchanged (the backend checks a language only when it changes, 3.3c).
  const options = languages?.status === 'loaded' ? [...languages.list] : []
  if (original && !options.some((l) => l.id === original.language)) {
    options.push({ id: original.language, minMemoryMb: 0 })
  }

  function change(field: Field, value: string) {
    setValues((current) => ({ ...current, [field]: value }))
  }

  function validate(): { errors: FieldErrors; body: AssignmentBody | null } {
    const errors: FieldErrors = {}
    const title = values.title.trim()
    if (title === '') {
      errors.title = 'Enter a title'
    } else if (title.length > TITLE_MAX_LENGTH) {
      errors.title = `Use at most ${TITLE_MAX_LENGTH} characters`
    }
    if (values.description.trim() === '') {
      errors.description = 'Enter a description'
    } else if (values.description.length > DESCRIPTION_MAX_LENGTH) {
      errors.description = `Use at most ${DESCRIPTION_MAX_LENGTH} characters`
    }
    if (values.language === '') {
      errors.language = 'Choose a language'
    }
    // An untouched deadline is sent exactly as stored, so it is never a change (8.0f).
    const deadlineTouched =
      !original || values.deadline !== toLocalInput(original.deadline)
    const deadline = deadlineTouched
      ? fromLocalInput(values.deadline)
      : original.deadline
    if (deadline === null) {
      errors.deadline = 'Enter the date and time of the deadline'
    }
    let maxAttempts: number | null = null
    if (values.maxAttempts.trim() !== '') {
      maxAttempts = wholeNumber(values.maxAttempts, 1, MAX_ATTEMPTS_MAX)
      if (maxAttempts === null) {
        errors.maxAttempts = `Use a whole number from 1 to ${MAX_ATTEMPTS_MAX}, or leave it empty`
      }
    }
    const timeLimitMs = wholeNumber(values.timeLimitMs, 1, TIME_LIMIT_MAX_MS)
    if (timeLimitMs === null) {
      errors.timeLimitMs = `Use a whole number from 1 to ${TIME_LIMIT_MAX_MS}`
    }
    const memoryLimitMb = wholeNumber(
      values.memoryLimitMb,
      1,
      MEMORY_LIMIT_MAX_MB,
    )
    const language = options.find((l) => l.id === values.language)
    // Like the backend, the language's minimum is checked only when the language or the memory
    // limit changes, so an assignment made before a minimum existed stays editable (7.1c).
    const memoryChanged =
      !original ||
      values.language !== original.language ||
      memoryLimitMb !== original.memoryLimitMb
    if (memoryLimitMb === null) {
      errors.memoryLimitMb = `Use a whole number from 1 to ${MEMORY_LIMIT_MAX_MB}`
    } else if (
      memoryChanged &&
      language &&
      memoryLimitMb < language.minMemoryMb
    ) {
      errors.memoryLimitMb = `${language.id} needs at least ${language.minMemoryMb} MB`
    }
    if (values.starterCode.length > STARTER_CODE_MAX_LENGTH) {
      errors.starterCode = `Use at most ${STARTER_CODE_MAX_LENGTH} characters`
    }
    if (Object.keys(errors).length > 0) {
      return { errors, body: null }
    }
    return {
      errors,
      body: {
        title,
        description: values.description,
        language: values.language,
        deadline: deadline!,
        maxAttempts,
        timeLimitMs: timeLimitMs!,
        memoryLimitMb: memoryLimitMb!,
        starterCode:
          values.starterCode.trim() === '' ? null : values.starterCode,
      },
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) {
      return
    }
    const { errors, body } = validate()
    setFieldErrors(errors)
    setFormError(null)
    if (!body) {
      return
    }
    setSubmitting(true)
    try {
      await onSubmit(body)
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
    } else if (isApiError(error) && CODE_FIELDS[error.code]) {
      setFieldErrors(
        Object.fromEntries(
          CODE_FIELDS[error.code].map((f) => [f, error.message]),
        ),
      )
    } else if (isApiError(error)) {
      setFormError(error.message)
    } else {
      setFormError('Something went wrong. Please try again.')
    }
  }

  const languageError =
    fieldErrors.language ??
    (languages?.status === 'failed' ? languages.message : undefined)

  return (
    <form onSubmit={submit} noValidate aria-label="Assignment">
      <TextField
        form="assignment"
        field="title"
        label="Title"
        autoComplete="off"
        value={values.title}
        error={fieldErrors.title}
        onChange={change}
      />
      <TextField
        form="assignment"
        field="description"
        label="Description"
        rows={6}
        autoComplete="off"
        value={values.description}
        error={fieldErrors.description}
        onChange={change}
      />
      <div>
        <label htmlFor="assignment-language">Language</label>
        <select
          id="assignment-language"
          name="language"
          value={values.language}
          disabled={gradingLocked || languages === null}
          aria-invalid={languageError ? true : undefined}
          aria-describedby={
            languageError ? 'assignment-language-error' : undefined
          }
          onChange={(event) => change('language', event.target.value)}
        >
          <option value="">
            {languages === null ? 'Loading languages…' : 'Choose a language'}
          </option>
          {options.map((l) => (
            <option key={l.id} value={l.id}>
              {l.id}
            </option>
          ))}
        </select>
        {languageError && <p id="assignment-language-error">{languageError}</p>}
      </div>
      <TextField
        form="assignment"
        field="deadline"
        label="Deadline (your local time)"
        type="datetime-local"
        autoComplete="off"
        value={values.deadline}
        error={fieldErrors.deadline}
        onChange={change}
      />
      <TextField
        form="assignment"
        field="maxAttempts"
        label="Attempts allowed (empty for unlimited)"
        type="number"
        autoComplete="off"
        value={values.maxAttempts}
        error={fieldErrors.maxAttempts}
        onChange={change}
      />
      <TextField
        form="assignment"
        field="timeLimitMs"
        label="Time limit per test (ms)"
        type="number"
        disabled={gradingLocked}
        autoComplete="off"
        value={values.timeLimitMs}
        error={fieldErrors.timeLimitMs}
        onChange={change}
      />
      <TextField
        form="assignment"
        field="memoryLimitMb"
        label="Memory limit (MB)"
        type="number"
        disabled={gradingLocked}
        autoComplete="off"
        value={values.memoryLimitMb}
        error={fieldErrors.memoryLimitMb}
        onChange={change}
      />
      {gradingLocked && (
        <p>
          The language and limits cannot change while the assignment is
          published: unpublish it first.
        </p>
      )}
      <TextField
        form="assignment"
        field="starterCode"
        label="Starter code (optional)"
        rows={8}
        autoComplete="off"
        value={values.starterCode}
        error={fieldErrors.starterCode}
        onChange={change}
      />
      {formError && <p role="alert">{formError}</p>}
      <button type="submit" disabled={submitting}>
        {submitting ? submittingLabel : submitLabel}
      </button>
    </form>
  )
}

export default AssignmentForm
