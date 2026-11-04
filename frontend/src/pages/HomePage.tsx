import { Link, Navigate } from 'react-router'
import { homeOf, useAuth } from '../auth/authContext'

// The start page (8.4a): a guest reads what the platform does and signs in or up; a signed-in user
// is sent on to their role's home. While GET /auth/me is open it waits, so a signed-in user never
// sees the guest page flash by.
function HomePage() {
  const { state } = useAuth()
  if (state.status === 'loading') {
    return <p role="status">Checking your sign-in…</p>
  }
  if (state.status === 'signedIn') {
    return <Navigate to={homeOf(state.user.role)} replace />
  }
  return (
    <main>
      <h1>Grading platform</h1>
      <p>
        Programming assignments, graded automatically. Submit your code and
        within seconds see which tests passed, your score on the assignment’s
        rubric, and hints on how to improve.
      </p>

      <h2>For students</h2>
      <ul>
        <li>Join a course with the code your teacher gives you.</li>
        <li>
          Submit Java or Python code: it runs against the assignment’s tests in
          an isolated sandbox.
        </li>
        <li>
          See your grade per rubric criterion, and the results of every public
          test.
        </li>
        <li>
          Ask for up to three hints, each a step more concrete. Hints explain;
          they never give away the solution and never change your score.
        </li>
      </ul>

      <h2>For teachers</h2>
      <ul>
        <li>Create courses and assignments with public and hidden tests.</li>
        <li>
          Weigh a rubric: test results are scored the same way every time.
        </li>
        <li>Review every submission and add marks for criteria you grade.</li>
      </ul>

      <p>
        <Link to="/signin">Sign in</Link> or{' '}
        <Link to="/signup">create a student account</Link>.
      </p>
    </main>
  )
}

export default HomePage
