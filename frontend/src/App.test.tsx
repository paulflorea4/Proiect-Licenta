import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('App', () => {
  it('renders the starter heading', () => {
    render(<App />)

    expect(
      screen.getByRole('heading', { level: 1, name: 'Get started' }),
    ).toBeInTheDocument()
  })

  it('starts the counter at zero', () => {
    render(<App />)

    expect(
      screen.getByRole('button', { name: 'Count is 0' }),
    ).toBeInTheDocument()
  })

  it('increments the counter on each click', async () => {
    const user = userEvent.setup()
    render(<App />)

    await user.click(screen.getByRole('button', { name: 'Count is 0' }))
    await user.click(screen.getByRole('button', { name: 'Count is 1' }))

    expect(
      screen.getByRole('button', { name: 'Count is 2' }),
    ).toBeInTheDocument()
  })
})
