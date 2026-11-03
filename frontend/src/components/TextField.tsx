interface TextFieldProps<F extends string> {
  /** Prefix of the input's id, unique per form (`signup`, `signin`, ...). */
  form: string
  field: F
  label: string
  type?: 'text' | 'email' | 'password'
  autoComplete: string
  value: string
  error: string | undefined
  onChange: (field: F, value: string) => void
}

// One labelled input of a form (8.3a, shared since 8.3b). Its error, whether from the page's own
// checks or the backend's, is shown under it and linked by aria-describedby, with aria-invalid.
function TextField<F extends string>({
  form,
  field,
  label,
  type = 'text',
  autoComplete,
  value,
  error,
  onChange,
}: TextFieldProps<F>) {
  const id = `${form}-${field}`
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

export default TextField
