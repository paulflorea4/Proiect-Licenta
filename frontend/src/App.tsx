import { Route, Routes } from 'react-router'
import AuthProvider from './auth/AuthProvider'
import CoursesPage from './pages/CoursesPage'
import HomePage from './pages/HomePage'
import NotFoundPage from './pages/NotFoundPage'
import SignInPage from './pages/SignInPage'
import SignUpPage from './pages/SignUpPage'

// The auth state (8.3b) and the routes: the router itself is in main.tsx (BrowserRouter), so tests
// can render App inside a MemoryRouter at any path and get the whole app, auth included.
function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/signin" element={<SignInPage />} />
        <Route path="/signup" element={<SignUpPage />} />
        <Route path="/courses" element={<CoursesPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </AuthProvider>
  )
}

export default App
