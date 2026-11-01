import { Route, Routes } from 'react-router'
import CoursesPage from './pages/CoursesPage'
import HomePage from './pages/HomePage'
import NotFoundPage from './pages/NotFoundPage'
import SignInPage from './pages/SignInPage'
import SignUpPage from './pages/SignUpPage'

// The routes only: the router itself is in main.tsx (BrowserRouter), so tests can render App
// inside a MemoryRouter at any path.
function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/signin" element={<SignInPage />} />
      <Route path="/signup" element={<SignUpPage />} />
      <Route path="/courses" element={<CoursesPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}

export default App
