interface TextFieldProps<F extends string> {
  /** Prefix of the input's id, unique per form (`signup`, `signin`, ...). */
  form: string
  field: F
  label: string
  type?: 'text' | 'email' | 'password' | 'number' | 'datetime-local'
  /** A multi-line `<textarea>` with this many rows instead of an `<input>` (9.1a). */
  rows?: number
  /** Shown but not editable (9.2b: a published assignment's language and limits). */
  disabled?: boolean
  autoComplete: string
  value: string
  error: string | undefined
  onChange: (field: F, value: string) => void
}

// One labelled input of a form (8.3a, shared since 8.3b), or a textarea when given `rows` (9.1a).
// Its error, whether from the page's own checks or the backend's, is shown under it and linked by
// aria-describedby, with aria-invalid.
function TextField<F extends string>({
  form,
  field,
  label,
  type = 'text',
  rows,
  disabled,
  autoComplete,
  value,
  error,
  onChange,
}: TextFieldProps<F>) {
  const id = `${form}-${field}`
  const shared = {
    id,
    name: field,
    autoComplete,
    value,
    disabled,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': error ? `${id}-error` : undefined,
  }
  return (
    <div>
      <label htmlFor={id}>{label}</label>
      {rows ? (
        <textarea
          {...shared}
          rows={rows}
          onChange={(event) => onChange(field, event.target.value)}
        />
      ) : (
        <input
          {...shared}
          type={type}
          onChange={(event) => onChange(field, event.target.value)}
        />
      )}
      {error && <p id={`${id}-error`}>{error}</p>}
    </div>
  )
}

export default TextField
