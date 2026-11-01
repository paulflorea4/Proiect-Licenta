import { Link } from 'react-router'

// Any path no route matches, so a mistyped URL is not a blank page.
function NotFoundPage() {
  return (
    <main>
      <h1>Page not found</h1>
      <p>
        <Link to="/">Back to the start page</Link>
      </p>
    </main>
  )
}

export default NotFoundPage
