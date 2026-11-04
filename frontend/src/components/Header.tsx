import { useState } from 'react'
import { Link, NavLink } from 'react-router'
import { useAuth } from '../auth/authContext'
import type { Role } from '../auth/authContext'

interface MenuItem {
  to: string
  label: string
}

// The menu per role (8.5a), the one place it is written. Only links a role can use: the backend
// still decides every access, this just doesn't offer what would be refused.
const MENU: Record<Role, readonly MenuItem[]> = {
  STUDENT: [{ to: '/courses', label: 'Courses' }],
  TEACHER: [{ to: '/courses', label: 'Courses' }],
  ADMIN: [
    { to: '/admin/users', label: 'Users' },
    { to: '/courses', label: 'Courses' },
  ],
}

const GUEST_MENU: readonly MenuItem[] = [
  { to: '/signin', label: 'Sign in' },
  { to: '/signup', label: 'Sign up' },
]

const ROLE_NAMES: Record<Role, string> = {
  STUDENT: 'Student',
  TEACHER: 'Teacher',
  ADMIN: 'Admin',
}

// The header on every page: the app's name (a link to /), the menu for the signed-in user's role
// (or sign in / sign up for a guest) and, when signed in, who it is and a sign-out button. While
// GET /auth/me is open it shows only the name, so no menu flashes by before the role is known.
//
// Signing out does not navigate: the route guards own that. On a guarded page the guard sends the
// user to /signin (remembering the page, so signing in again returns there); on a public page they
// stay. A jump to / of its own raced the guard's redirect and lost.
function Header() {
  const auth = useAuth()
  const [signingOut, setSigningOut] = useState(false)
  const [signOutFailed, setSignOutFailed] = useState(false)
  const { state } = auth

  async function signOut() {
    setSigningOut(true)
    setSignOutFailed(false)
    try {
      await auth.signOut()
    } catch {
      setSignOutFailed(true)
    } finally {
      setSigningOut(false)
    }
  }

  const menu =
    state.status === 'signedIn'
      ? MENU[state.user.role]
      : state.status === 'signedOut'
        ? GUEST_MENU
        : []

  return (
    <header>
      <Link to="/">Grading platform</Link>
      {menu.length > 0 && (
        <nav aria-label="Main">
          <ul>
            {menu.map((item) => (
              <li key={item.to}>
                <NavLink to={item.to}>{item.label}</NavLink>
              </li>
            ))}
          </ul>
        </nav>
      )}
      {state.status === 'signedIn' && (
        <div>
          <span>
            Signed in as {state.user.fullName} ({ROLE_NAMES[state.user.role]})
          </span>{' '}
          <button
            type="button"
            onClick={() => void signOut()}
            disabled={signingOut}
          >
            {signingOut ? 'Signing out…' : 'Sign out'}
          </button>
          {signOutFailed && (
            <p role="alert">Could not sign out. Please try again.</p>
          )}
        </div>
      )}
    </header>
  )
}

export default Header
