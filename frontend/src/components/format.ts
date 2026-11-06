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
