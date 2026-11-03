import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../api/client'
import { AuthContext } from './authContext'
import type { Auth, AuthState, User } from './authContext'

const SIGNED_OUT: AuthState = { status: 'signedOut', user: null }

// Asks `GET /auth/me` once on load: the browser sends the auth cookie by itself, so a 200 means
// signed in and anything else (401, a network failure, a server error) means signed out. A sign-in
// that lands while that question is still open wins: the question is abandoned.
function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    status: 'loading',
    user: null,
  })
  const check = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    check.current = controller
    api
      .get<User>('/auth/me', { signal: controller.signal })
      .then((user) => {
        if (!controller.signal.aborted) {
          setState({ status: 'signedIn', user })
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setState(SIGNED_OUT)
        }
      })
    return () => controller.abort()
  }, [])

  const signedIn = useCallback((user: User) => {
    check.current?.abort()
    setState({ status: 'signedIn', user })
  }, [])

  const signOut = useCallback(async () => {
    await api.post<void>('/auth/signout')
    check.current?.abort()
    setState(SIGNED_OUT)
  }, [])

  const auth = useMemo<Auth>(
    () => ({ state, signedIn, signOut }),
    [state, signedIn, signOut],
  )
  return <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>
}

export default AuthProvider
