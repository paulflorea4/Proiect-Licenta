import { Route, Routes } from 'react-router'
import AuthProvider from './auth/AuthProvider'
import { ProtectedRoute, RoleRoute } from './auth/routeGuards'
import AppShell from './components/AppShell'
import AdminUsersPage from './pages/AdminUsersPage'
import CoursePage from './pages/CoursePage'
import CoursesPage from './pages/CoursesPage'
import EditAssignmentPage from './pages/EditAssignmentPage'
import HomePage from './pages/HomePage'
import NewAssignmentPage from './pages/NewAssignmentPage'
import NotFoundPage from './pages/NotFoundPage'
import SignInPage from './pages/SignInPage'
import SignUpPage from './pages/SignUpPage'

// The auth state (8.3b) and the routes: the router itself is in main.tsx (BrowserRouter), so tests
// can render App inside a MemoryRouter at any path and get the whole app, auth included.
function App() {
  return (
    <AuthProvider>
      <Routes>
        {/* The header on every page, the not-found page included (8.5a). */}
        <Route element={<AppShell />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/signin" element={<SignInPage />} />
          <Route path="/signup" element={<SignUpPage />} />
          {/* Signed-in users only (8.3c). */}
          <Route element={<ProtectedRoute />}>
            <Route path="/courses" element={<CoursesPage />} />
            <Route path="/courses/:courseId" element={<CoursePage />} />
          </Route>
          {/* Teachers only: creating an assignment (9.2b; POST is teachers only, 3.3a). */}
          <Route element={<RoleRoute roles={['TEACHER']} />}>
            <Route
              path="/courses/:courseId/assignments/new"
              element={<NewAssignmentPage />}
            />
          </Route>
          {/* The people who run a course: changing an assignment (9.2b). */}
          <Route element={<RoleRoute roles={['TEACHER', 'ADMIN']} />}>
            <Route
              path="/assignments/:assignmentId/edit"
              element={<EditAssignmentPage />}
            />
          </Route>
          {/* Admins only (8.4a: the admin's home). */}
          <Route element={<RoleRoute roles={['ADMIN']} />}>
            <Route path="/admin/users" element={<AdminUsersPage />} />
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </AuthProvider>
  )
}

export default App
