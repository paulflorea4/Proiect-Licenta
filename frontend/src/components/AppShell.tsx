import { Outlet } from 'react-router'
import Header from './Header'

// The frame of every page (8.5a), as a layout route around all routes: the header, then the page.
function AppShell() {
  return (
    <>
      <Header />
      <Outlet />
    </>
  )
}

export default AppShell
