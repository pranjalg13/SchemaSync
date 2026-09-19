import { useEffect, useRef, useState, type ReactNode } from 'react'

/**
 * Runs an async action from a form, keeping the form open if it fails.
 *
 * The first version closed the dialog the moment Save was clicked and reported failures in a
 * toast -- so a rejected name (a clash, a reserved word) threw away everything the user had typed.
 * Now the dialog stays, shows the server's reason inline, and closes only on success.
 */
export function useSubmit(action: () => Promise<void>, onDone: () => void) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    if (pending) return
    setPending(true)
    setError(null)
    try {
      await action()
      onDone()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setPending(false)
    }
  }
  return { submit, pending, error }
}

/**
 * A global keyboard shortcut that stays out of the way while the user is typing.
 * Plain keys are ignored inside inputs; `meta` shortcuts (Cmd/Ctrl+key) work everywhere.
 */
export function useHotkey(key: string, handler: (e: KeyboardEvent) => void,
                          opts: { meta?: boolean; enabled?: boolean } = {}) {
  const ref = useRef(handler)
  ref.current = handler
  useEffect(() => {
    if (opts.enabled === false) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== key) return
      const typing = (e.target as HTMLElement)?.closest?.('input, textarea, select, [contenteditable]')
      if (opts.meta) {
        if (!(e.metaKey || e.ctrlKey)) return
      } else if (typing || e.metaKey || e.ctrlKey || e.altKey) {
        return
      }
      e.preventDefault()
      ref.current(e)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [key, opts.meta, opts.enabled])
}

export function Modal({
  title, subtitle, onClose, onSubmit, children, footer,
}: {
  title: string
  subtitle?: string
  onClose: () => void
  /** Bound to Cmd/Ctrl+Enter, so a form can be completed without reaching for the mouse. */
  onSubmit?: () => void
  children: ReactNode
  footer?: ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && onSubmit) {
        e.preventDefault()
        onSubmit()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose, onSubmit])

  useEffect(() => {
    // Focus the first field so the dialog is usable straight away.
    ref.current?.querySelector<HTMLElement>('input, select, textarea')?.focus()
  }, [])

  return (
    <div className="overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="modal" ref={ref} role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-head">
          <div>
            <h2>{title}</h2>
            {subtitle && <p className="muted small">{subtitle}</p>}
          </div>
          <button className="icon" onClick={onClose} aria-label="Close">&times;</button>
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-foot">{footer}</footer>}
      </div>
    </div>
  )
}

export function FormError({ error }: { error: string | null }) {
  if (!error) return null
  return <p className="form-error" role="alert">{error}</p>
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="kbd">{children}</kbd>
}

export const SUBMIT_HINT = navigator.platform?.toLowerCase().includes('mac') ? '⌘↵' : 'Ctrl↵'

export function Field({
  label, hint, children,
}: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  )
}

/**
 * Common Postgres types, offered as suggestions rather than a closed list: the type space is open
 * (domains, extension types), and someone who wants numeric(14,2) should just be able to type it.
 */
export const COMMON_TYPES = [
  'text', 'varchar(255)', 'varchar(64)', 'varchar(32)',
  'integer', 'bigint', 'smallint', 'numeric(14,2)', 'numeric',
  'boolean', 'timestamptz', 'timestamp', 'date', 'uuid', 'jsonb',
]

export function TypeInput({ value, onChange, id }: {
  value: string; onChange: (v: string) => void; id: string
}) {
  return (
    <>
      <input list={id} value={value} onChange={(e) => onChange(e.target.value)}
             spellCheck={false} autoComplete="off" className="mono-input" />
      <datalist id={id}>{COMMON_TYPES.map((t) => <option key={t} value={t} />)}</datalist>
    </>
  )
}

export function Toast({ message, tone, onDone }: {
  message: string; tone: 'ok' | 'error'; onDone: () => void
}) {
  useEffect(() => {
    const t = setTimeout(onDone, tone === 'error' ? 8000 : 3200)
    return () => clearTimeout(t)
  }, [onDone, tone])
  return (
    <div className={`toast toast-${tone}`} role="status">
      <span>{message}</span>
      <button className="icon" onClick={onDone} aria-label="Dismiss">&times;</button>
    </div>
  )
}

/** Thin indeterminate bar pinned to the top of the viewport while anything is loading. */
export function TopProgress({ active }: { active: boolean }) {
  return <div className={`top-progress ${active ? 'on' : ''}`} aria-hidden="true" />
}

export function Skeleton({ rows = 3 }: { rows?: number }) {
  return (
    <section className="card skeleton-card" aria-busy="true">
      <div className="skeleton skeleton-title" />
      {Array.from({ length: rows }).map((_, i) => <div key={i} className="skeleton skeleton-row" />)}
    </section>
  )
}

export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <button
      className="link"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text)
          setCopied(true)
          setTimeout(() => setCopied(false), 1400)
        } catch { /* clipboard unavailable (e.g. insecure origin); nothing useful to do */ }
      }}
    >
      {copied ? 'Copied' : label}
    </button>
  )
}

export function timeAgo(iso: string): string {
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000)
  if (seconds < 45) return 'just now'
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.round(seconds / 3600)}h ago`
  return `${Math.round(seconds / 86400)}d ago`
}

/**
 * Deletion that requires typing the object's name. Deliberately more friction than an OK button:
 * these actions delete a column of production data on merge, and a reflexive click is how that
 * happens.
 */
export function ConfirmDestructive({
  title, what, consequence, onConfirm, onClose,
}: {
  title: string; what: string; consequence: string
  onConfirm: () => Promise<void>; onClose: () => void
}) {
  const [typed, setTyped] = useState('')
  const { submit, pending, error } = useSubmit(onConfirm, onClose)
  const ready = typed === what
  const go = () => { if (ready) submit() }
  return (
    <Modal
      title={title}
      onClose={onClose}
      onSubmit={go}
      footer={
        <>
          <button onClick={onClose}>Cancel</button>
          <button className="destructive" disabled={!ready || pending} onClick={go}>
            {pending ? 'Dropping…' : `Drop ${what}`}
          </button>
        </>
      }
    >
      <p className="warn-text">{consequence}</p>
      <Field label={`Type “${what}” to confirm`}>
        <input value={typed} onChange={(e) => setTyped(e.target.value)} spellCheck={false}
               autoComplete="off" className="mono-input"
               onKeyDown={(e) => { if (e.key === 'Enter') go() }} />
      </Field>
      <FormError error={error} />
    </Modal>
  )
}
