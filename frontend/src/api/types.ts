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

/**
 * An assignment (`AssignmentResponse`, 3.3b). `published`, `createdAt` and `updatedAt` are sent only
 * to the people who run the course; a student's JSON has no such keys (a student sees published
 * assignments only).
 */
export interface Assignment {
  id: number
  courseId: number
  title: string
  description: string
  language: string
  deadline: string
  maxAttempts: number | null
  timeLimitMs: number
  memoryLimitMb: number
  starterCode: string | null
  published?: boolean
  createdAt?: string
  updatedAt?: string
}

/** `GET /assignments/{id}/my-grade` (5.4c): the attempt whose score counts, or `null` while none is graded. */
export interface MyGrade {
  assignmentId: number
  best: {
    submissionId: number
    attemptNo: number
    totalScore: number
    maxScore: number
    gradedAt: string
    provisional: boolean
  } | null
}

/** `GET /languages` (9.2b): a language an assignment may use, and the smallest memory limit it accepts. */
export interface Language {
  id: string
  minMemoryMb: number
}

/** The body of `POST /courses/{id}/assignments` and `PUT /assignments/{id}` (`AssignmentRequest`, 3.3a). */
export interface AssignmentBody {
  title: string
  description: string
  language: string
  deadline: string
  maxAttempts: number | null
  timeLimitMs: number
  memoryLimitMb: number
  starterCode: string | null
}
