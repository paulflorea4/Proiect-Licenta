import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { api, isApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import { useAuth } from '../auth/authContext'
import type { Role, User } from '../auth/authContext'
import Pager from '../components/Pager'
import { pageNumberOf } from '../components/pagination'

/** A row of `GET /admin/users` (`UserSummaryResponse`). */
interface UserRow {
  id: number
  email: string
  role: Role
}

const ROLES: readonly Role[] = ['STUDENT', 'TEACHER', 'ADMIN']
const ROLE_NAMES: Record<Role, string> = {
  STUDENT: 'Student',
  TEACHER: 'Teacher',
  ADMIN: 'Admin',
}

// The last answer and the page it is for; it is "loading" while that is not the page in the URL
// (state is only set from the request's callbacks, never in the effect itself).
type Result =
  | { forPage: number; status: 'failed'; message: string }
  | { forPage: number; status: 'loaded'; page: PageResponse<UserRow> }

type Load = { status: 'loading' } | Result

function messageOf(error: unknown): string {
  return isApiError(error)
    ? error.message
    : 'Something went wrong. Please try again.'
}

// The admin's home (8.5b): every account, oldest first (GET /admin/users, 2.5c), with a role
// selector per row (PATCH /admin/users/{id}/role, 2.5b), the only way a teacher or an admin comes
// to exist. The backend enforces every rule (admins only, the last admin, 7.7e's stored-role check);
// the page shows its answer.
function AdminUsersPage() {
  const auth = useAuth()
  const [searchParams, setSearchParams] = useSearchParams()
  const pageNumber = pageNumberOf(searchParams.get('page'))
  const [result, setResult] = useState<Result | null>(null)
  const load: Load =
    result && result.forPage === pageNumber ? result : { status: 'loading' }
  const [savingId, setSavingId] = useState<number | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [changeError, setChangeError] = useState<string | null>(null)
  const me = auth.state.status === 'signedIn' ? auth.state.user : null

  useEffect(() => {
    const controller = new AbortController()
    api
      .get<PageResponse<UserRow>>(`/admin/users?page=${pageNumber - 1}`, {
        signal: controller.signal,
      })
      .then((page) =>
        setResult({ forPage: pageNumber, status: 'loaded', page }),
      )
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setResult({
            forPage: pageNumber,
            status: 'failed',
            message: messageOf(error),
          })
        }
      })
    return () => controller.abort()
  }, [pageNumber])

  function goTo(n: number) {
    setNotice(null)
    setChangeError(null)
    setSearchParams(n === 1 ? {} : { page: String(n) })
  }

  async function changeRole(row: UserRow, role: Role) {
    setSavingId(row.id)
    setNotice(null)
    setChangeError(null)
    try {
      const updated = await api.patch<User>(`/admin/users/${row.id}/role`, {
        role,
      })
      setResult((current) =>
        current?.status === 'loaded'
          ? {
              ...current,
              page: {
                ...current.page,
                items: current.page.items.map((item) =>
                  item.id === updated.id
                    ? { ...item, role: updated.role }
                    : item,
                ),
              },
            }
          : current,
      )
      setNotice(`${updated.email} is now ${ROLE_NAMES[updated.role]}.`)
      if (me && updated.id === me.id && updated.role !== me.role) {
        // The backend already treats the stored role as theirs (7.7e): follow it here at once.
        auth.signedIn({ ...me, role: updated.role })
      }
    } catch (error) {
      setChangeError(messageOf(error))
    } finally {
      setSavingId(null)
    }
  }

  return (
    <main>
      <h1>Users</h1>
      {load.status === 'loading' && <p role="status">Loading users…</p>}
      {load.status === 'failed' && <p role="alert">{load.message}</p>}
      {load.status === 'loaded' && (
        <>
          {notice && <p role="status">{notice}</p>}
          {changeError && <p role="alert">{changeError}</p>}
          {load.page.items.length === 0 ? (
            <p>No users on this page.</p>
          ) : (
            <table>
              <caption>All accounts, oldest first</caption>
              <thead>
                <tr>
                  <th scope="col">Email</th>
                  <th scope="col">Role</th>
                </tr>
              </thead>
              <tbody>
                {load.page.items.map((row) => (
                  <tr key={row.id}>
                    <td>
                      {row.email}
                      {me?.id === row.id && ' (you)'}
                    </td>
                    <td>
                      <select
                        aria-label={`Role of ${row.email}`}
                        value={row.role}
                        disabled={savingId !== null}
                        onChange={(event) =>
                          void changeRole(row, event.target.value as Role)
                        }
                      >
                        {ROLES.map((role) => (
                          <option key={role} value={role}>
                            {ROLE_NAMES[role]}
                          </option>
                        ))}
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <Pager
            pageNumber={pageNumber}
            totalPages={load.page.totalPages}
            totalElements={load.page.totalElements}
            noun={['user', 'users']}
            onGoTo={goTo}
          />
        </>
      )}
    </main>
  )
}

export default AdminUsersPage
