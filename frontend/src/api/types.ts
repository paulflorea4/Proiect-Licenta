/** The backend's one shape for a paginated list (2.5c, `PageResponse`); `page` is 0-based. */
export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
