import { createContext, useContext } from 'react'

/** A user as the API shows them (`UserResponse`: signup, signin, `GET /auth/me`). */
export interface User {
  id: number
  email: string
  fullName: string
  role: 'STUDENT' | 'TEACHER' | 'ADMIN'
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
