/**
 * A moment for people, in their own time zone and language (9.2a): "Nov 12, 2026, 11:59 PM". Put it
 * in a `<time dateTime={iso}>` so the exact instant stays in the page.
 */
export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(iso))
}

/**
 * A score as the backend keeps it (`NUMERIC(7, 2)`, 5.4b), without trailing zeros: 85.5, 100,
 * 12.25. Not localised, so the decimal point is the same everywhere the score is shown.
 */
export function formatScore(score: number): string {
  return String(Number(score.toFixed(2)))
}

/**
 * An instant as a `<input type="datetime-local">` value in the user's own time zone (9.2b),
 * to the minute: "2026-11-12T23:59".
 */
export function toLocalInput(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * A `datetime-local` value, read in the user's own time zone, as an ISO instant for the API, or
 * null when it is not a complete date and time.
 */
export function fromLocalInput(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(value)) {
    return null
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}
