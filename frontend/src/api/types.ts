/** The backend's one shape for a paginated list (2.5c, `PageResponse`); `page` is 0-based. */
export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/**
 * A course (`CourseResponse`, 3.1a). `enrollCode` is sent only to the people who run it (its
 * teacher, an admin); a student's JSON has no such key (3.1b).
 */
export interface Course {
  id: number
  title: string
  description: string | null
  teacherId: number
  enrollCode?: string
  createdAt: string
}
