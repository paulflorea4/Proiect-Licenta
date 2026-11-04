interface PagerProps {
  /** The page shown, 1-based. */
  pageNumber: number
  totalPages: number
  totalElements: number
  /** What the list counts, singular and plural: `['user', 'users']`. */
  noun: readonly [string, string]
  onGoTo: (pageNumber: number) => void
}

// Previous / Next under a paginated list (8.5b, shared since 9.1a), with "Page X of Y (N users)".
// The list page keeps its page in the URL (see pagination.ts) and passes it here.
function Pager({
  pageNumber,
  totalPages,
  totalElements,
  noun,
  onGoTo,
}: PagerProps) {
  return (
    <nav aria-label="Pages">
      <button
        type="button"
        disabled={pageNumber <= 1}
        onClick={() => onGoTo(pageNumber - 1)}
      >
        Previous
      </button>{' '}
      <span>
        Page {pageNumber} of {Math.max(totalPages, 1)} ({totalElements}{' '}
        {totalElements === 1 ? noun[0] : noun[1]})
      </span>{' '}
      <button
        type="button"
        disabled={pageNumber >= totalPages}
        onClick={() => onGoTo(pageNumber + 1)}
      >
        Next
      </button>
    </nav>
  )
}

export default Pager
