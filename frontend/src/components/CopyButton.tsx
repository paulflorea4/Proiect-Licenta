import { useState } from 'react'

interface CopyButtonProps {
  /** What is put on the clipboard. */
  text: string
  /** The button's accessible name, saying what it copies: "Copy the enrollment code of Algorithms". */
  label: string
}

// A "Copy" button for a short text such as an enrollment code (9.1a). The result is announced in
// a status region next to it; where the browser refuses the clipboard (no secure context, denied
// permission) it says so, and the text stays on screen to be selected by hand.
function CopyButton({ text, label }: CopyButtonProps) {
  const [result, setResult] = useState<'copied' | 'failed' | null>(null)

  async function copy() {
    try {
      // `navigator.clipboard` is missing outside a secure context: that throws here too.
      await navigator.clipboard.writeText(text)
      setResult('copied')
    } catch {
      setResult('failed')
    }
  }

  return (
    <>
      <button type="button" aria-label={label} onClick={() => void copy()}>
        Copy
      </button>{' '}
      <span role="status">
        {result === 'copied' && 'Copied'}
        {result === 'failed' && 'Could not copy: select the code and copy it'}
      </span>
    </>
  )
}

export default CopyButton
