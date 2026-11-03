import type { ApiError } from '../api/client'

/**
 * A backend `VALIDATION_FAILED`'s field errors, keyed by the form's own field names (the request
 * record's component names), first message per field; fields the form does not have are dropped.
 * Empty when none matched: the caller then shows `error.message` for the whole form.
 */
export function fieldErrorsOf<F extends string>(
  error: ApiError,
  fields: readonly F[],
): Partial<Record<F, string>> {
  const errors: Partial<Record<F, string>> = {}
  for (const { field, message } of error.fieldErrors) {
    if ((fields as readonly string[]).includes(field)) {
      errors[field as F] ??= message
    }
  }
  return errors
}
