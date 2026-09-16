import { useEffect, useRef, type ReactNode } from 'react'

/**
 * A modal dialog.
 *
 * Replaces the browser's prompt(), which could only ask one question at a time -- so changing a
 * column's name and type meant two popups and two separate commits. A form can ask for everything
 * at once and send one commit, which is both less clicking and a cleaner history.
 */
export function Modal({
  title, subtitle, onClose, children, footer,
}: {
  title: string
  subtitle?: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    // Focus the first field so the dialog is usable without reaching for the mouse.
    ref.current?.querySelector<HTMLElement>('input, select, textarea, button')?.focus()
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

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
 * Common Postgres types, offered as suggestions rather than a closed list.
 *
 * A dropdown would be wrong here: the type space is open (domains, extension types), and a user
 * who knows they want `numeric(14,2)` should just be able to type it.
 */
export const COMMON_TYPES = [
  'text', 'varchar(255)', 'varchar(64)', 'varchar(32)',
  'integer', 'bigint', 'smallint', 'numeric(14,2)', 'numeric',
  'boolean', 'timestamptz', 'timestamp', 'date', 'uuid', 'jsonb',
]

export function TypeInput({
  value, onChange, id,
}: { value: string; onChange: (v: string) => void; id: string }) {
  return (
    <>
      <input
        list={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        spellCheck={false}
        autoComplete="off"
      />
      <datalist id={id}>
        {COMMON_TYPES.map((t) => <option key={t} value={t} />)}
      </datalist>
    </>
  )
}

/** Transient confirmation, so an action that succeeds does not just silently redraw. */
export function Toast({ message, tone, onDone }: {
  message: string; tone: 'ok' | 'error'; onDone: () => void
}) {
  useEffect(() => {
    const t = setTimeout(onDone, tone === 'error' ? 8000 : 3500)
    return () => clearTimeout(t)
  }, [onDone, tone])
  return (
    <div className={`toast toast-${tone}`} role="status">
      <span>{message}</span>
      <button className="icon" onClick={onDone} aria-label="Dismiss">&times;</button>
    </div>
  )
}

/**
 * A destructive confirmation that requires typing the object's name.
 *
 * Deliberately more friction than an OK button. These are the actions that delete a column of
 * production data on merge, and a reflexive click is exactly how that happens.
 */
export function ConfirmDestructive({
  title, what, consequence, onConfirm, onClose,
}: {
  title: string; what: string; consequence: string
  onConfirm: () => void; onClose: () => void
}) {
  const ref = useRef<HTMLInputElement>(null)
  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>Cancel</button>
          <button
            className="destructive"
            onClick={() => {
              if (ref.current?.value === what) { onConfirm(); onClose() }
              else ref.current?.focus()
            }}
          >
            Drop {what}
          </button>
        </>
      }
    >
      <p className="warn-text">{consequence}</p>
      <Field label={`Type “${what}” to confirm`}>
        <input ref={ref} spellCheck={false} autoComplete="off"
               onKeyDown={(e) => {
                 if (e.key === 'Enter' && ref.current?.value === what) { onConfirm(); onClose() }
               }} />
      </Field>
    </Modal>
  )
}
