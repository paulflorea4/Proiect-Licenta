import { Navigate, Outlet, useLocation } from 'react-router'
import { useAuth } from './authContext'
import type { Role } from './authContext'

// Route guards (8.3c), used as layout routes: <Route element={<ProtectedRoute />}> around the
// routes they guard. They are a convenience for the user only: the backend decides every access
// (2.5a), so a guard never stands in for an API check.

/** Shown while GET /auth/me has not answered: redirecting then would sign a signed-in user out. */
function Checking() {
  return <p role="status">Checking your sign-in…</p>
}

/** To `/signin`, remembering where the user was going (the sign-in page returns there). */
function ToSignIn() {
  const location = useLocation()
  return (
    <Navigate
      to="/signin"
      replace
      state={{ from: location.pathname + location.search }}
    />
  )
}

/** Its routes are for signed-in users; anyone else is sent to `/signin`. */
export function ProtectedRoute() {
  const { state } = useAuth()
  if (state.status === 'loading') {
    return <Checking />
  }
  if (state.status === 'signedOut') {
    return <ToSignIn />
  }
  return <Outlet />
}

/**
 * Its routes are for signed-in users with one of `roles`: a signed-out user is sent to `/signin`,
 * a signed-in one without the role to `/` (from 8.4a, their role's home).
 */
export function RoleRoute({ roles }: { roles: readonly Role[] }) {
  const { state } = useAuth()
  if (state.status === 'loading') {
    return <Checking />
  }
  if (state.status === 'signedOut') {
    return <ToSignIn />
  }
  if (!roles.includes(state.user.role)) {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
