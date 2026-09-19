import { useEffect, useRef, useState } from 'react'
import type { Branch } from './api'
import { Kbd } from './ui'

const STATUS_LABEL: Record<string, string> = {
  ACTIVE: 'active', MERGED: 'merged', DRIFTED: 'drifted', CREATING: 'creating',
}

/**
 * Branch picker with each branch's state visible.
 *
 * A native select can only show text, so "is this branch merged? drifted?" was hidden until after
 * you switched to it. The state decides what you can do with a branch, so it belongs in the picker.
 */
export function BranchSwitcher({ branches, current, onSelect, onNew }: {
  branches: Branch[]
  current: Branch | null
  onSelect: (b: Branch) => void
  onNew: () => void
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const main = branches.filter((b) => b.isMain)
  const active = branches.filter((b) => !b.isMain && b.status !== 'MERGED')
  const merged = branches.filter((b) => !b.isMain && b.status === 'MERGED')

  const item = (b: Branch) => (
    <button key={b.id} role="option" aria-selected={b.id === current?.id}
            className={`switcher-item ${b.id === current?.id ? 'on' : ''}`}
            onClick={() => { onSelect(b); setOpen(false) }}>
      <span className={`status-dot status-${b.status.toLowerCase()}`} />
      <span className="switcher-name">{b.name}</span>
      {b.isMain ? <span className="switcher-tag">read-only</span>
        : <span className="switcher-tag">{STATUS_LABEL[b.status] ?? b.status.toLowerCase()}</span>}
    </button>
  )

  return (
    <div className="switcher" ref={ref}>
      <button className="switcher-trigger" aria-haspopup="listbox" aria-expanded={open}
              onClick={() => setOpen((o) => !o)}>
        <span className={`status-dot status-${(current?.status ?? 'active').toLowerCase()}`} />
        <span className="switcher-name">{current?.name ?? 'Select a branch'}</span>
        <span className="chevron" aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className="switcher-menu" role="listbox">
          {main.map(item)}
          {active.length > 0 && <div className="switcher-group">Branches</div>}
          {active.map(item)}
          {merged.length > 0 && <div className="switcher-group">Merged</div>}
          {merged.map(item)}
          <div className="switcher-sep" />
          <button className="switcher-item switcher-new" onClick={() => { setOpen(false); onNew() }}>
            <span className="plus">+</span> New branch <Kbd>N</Kbd>
          </button>
        </div>
      )}
    </div>
  )
}
