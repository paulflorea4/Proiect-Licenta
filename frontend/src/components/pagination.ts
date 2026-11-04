/**
 * A list page's `?page=` (8.5b, shared since 9.1a): 1-based for people; anything that is not a
 * whole number from 1 is the first page. The API's `page` is this minus one.
 */
export function pageNumberOf(value: string | null): number {
  const n = Number(value)
  return Number.isInteger(n) && n >= 1 ? n : 1
}
