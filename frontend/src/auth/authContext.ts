import { createContext, useContext } from 'react'

/** The backend's `Role`, spelled as its JSON does. */
export type Role = 'STUDENT' | 'TEACHER' | 'ADMIN'

/** A user as the API shows them (`UserResponse`: signup, signin, `GET /auth/me`). */
export interface User {
  id: number
  email: string
  fullName: string
  role: Role
}

/**
 * Each role's home (8.4a): where `/` sends a signed-in user and where signin goes when no guarded
 * route sent them there. A teacher's courses get their management actions on the same page.
 */
const HOMES: Record<Role, string> = {
  STUDENT: '/courses',
  TEACHER: '/courses',
  ADMIN: '/admin/users',
}

export function homeOf(role: Role): string {
  return HOMES[role]
}

/**
 * The path a guarded route remembers for after signin (8.3c), as `location.state.from`. Only a
 * path of this app counts: anything else (another site, `//host`, the sign-in page itself) is
 * null, so a crafted state can never send the user elsewhere.
 */
export function returnPathOf(state: unknown): string | null {
  if (typeof state !== 'object' || state === null || !('from' in state)) {
    return null
  }
  const from = (state as { from: unknown }).from
  if (
    typeof from !== 'string' ||
    !from.startsWith('/') ||
    from.startsWith('//') ||
    from.startsWith('/\\') ||
    from === '/signin' ||
    from.startsWith('/signin?')
  ) {
    return null
  }
  return from
}

/**
 * Who is signed in, as far as the frontend knows (8.3b). The token itself is an `httpOnly` cookie
 * the frontend never sees: `loading` while `GET /auth/me` has not answered yet, then `signedIn`
 * with the user it returned or `signedOut`. Role checks made from this are a convenience only;
 * the backend enforces access.
 */
export type AuthState =
  | { status: 'loading'; user: null }
  | { status: 'signedIn'; user: User }
  | { status: 'signedOut'; user: null }

export interface Auth {
  state: AuthState
  /** Records the user a successful signin returned (the cookie is already set). */
  signedIn: (user: User) => void
  /** `POST /auth/signout`, then signed out; an `ApiError` is rethrown and the state kept. */
  signOut: () => Promise<void>
}

export const AuthContext = createContext<Auth | null>(null)

/** The current auth state; only inside `AuthProvider`. */
export function useAuth(): Auth {
  const auth = useContext(AuthContext)
  if (auth === null) {
    throw new Error('useAuth must be used inside AuthProvider')
  }
  return auth
}
