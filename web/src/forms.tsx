import { useState } from 'react'
import type { Branch, Column, Operation, Table } from './api'
import { Field, FormError, Kbd, Modal, SUBMIT_HINT, TypeInput, useSubmit } from './ui'

type Submit = (ops: Operation[], message: string) => Promise<void>

function Footer({ onClose, submit, pending, disabled, label, pendingLabel, note }: {
  onClose: () => void; submit: () => void; pending: boolean; disabled: boolean
  label: string; pendingLabel: string; note?: string
}) {
  return (
    <>
      <span className="muted small">{note ?? <>Press <Kbd>{SUBMIT_HINT}</Kbd> to save</>}</span>
      <button onClick={onClose}>Cancel</button>
      <button className="primary" disabled={disabled || pending} onClick={submit}>
        {pending ? pendingLabel : label}
      </button>
    </>
  )
}

/**
 * Edits every attribute of a column in one dialog, emitting only what changed.
 *
 * Renaming and retyping in one form produces ONE commit with both operations -- the honest record
 * of what the user did, and the shape the merge planner compiles best.
 */
export function ColumnEditor({ table, column, onSubmit, onClose }: {
  table: Table; column: Column; onSubmit: Submit; onClose: () => void
}) {
  const [name, setName] = useState(column.name)
  const [type, setType] = useState(column.type)
  const [nullable, setNullable] = useState(column.nullable)
  const [defaultExpr, setDefaultExpr] = useState(column.defaultExpr ?? '')
  const [fillValue, setFillValue] = useState("''")

  const trimmedDefault = defaultExpr.trim() === '' ? null : defaultExpr.trim()
  const becomingRequired = column.nullable && !nullable

  const ops: Operation[] = []
  const parts: string[] = []
  if (name !== column.name) {
    ops.push({ op: 'RENAME_COLUMN', tableId: table.id, columnId: column.id, newName: name.trim() })
    parts.push(`rename to ${name.trim()}`)
  }
  if (type !== column.type) {
    ops.push({ op: 'CHANGE_COLUMN_TYPE', tableId: table.id, columnId: column.id, newType: type })
    parts.push(`retype to ${type}`)
  }
  if (nullable !== column.nullable) {
    ops.push({ op: 'SET_COLUMN_NULLABLE', tableId: table.id, columnId: column.id,
               nullable, fillValue: becomingRequired ? fillValue : null })
    parts.push(nullable ? 'allow NULL' : 'require a value')
  }
  if (trimmedDefault !== (column.defaultExpr ?? null)) {
    ops.push({ op: 'SET_COLUMN_DEFAULT', tableId: table.id, columnId: column.id,
               defaultExpr: trimmedDefault })
    parts.push(trimmedDefault ? `default ${trimmedDefault}` : 'remove default')
  }

  const invalid = ops.length === 0 || name.trim() === ''
  const { submit, pending, error } = useSubmit(
    () => onSubmit(ops, `${column.name}: ${parts.join(', ')}`), onClose)
  const go = () => { if (!invalid) submit() }

  return (
    <Modal
      title={`Edit ${table.name}.${column.name}`}
      subtitle="Applied to this branch now, and to main only when you merge."
      onClose={onClose}
      onSubmit={go}
      footer={<Footer onClose={onClose} submit={go} pending={pending} disabled={invalid}
                      label="Save" pendingLabel="Saving…"
                      note={ops.length === 0 ? 'No changes yet'
                        : `${ops.length} change${ops.length === 1 ? '' : 's'}`} />}
    >
      <Field label="Name">
        <input value={name} onChange={(e) => setName(e.target.value)} spellCheck={false}
               className="mono-input" />
      </Field>
      <Field label="Type" hint={type !== column.type
        ? 'On a large table this runs online at merge time, without blocking reads or writes.'
        : undefined}>
        <TypeInput id="col-types" value={type} onChange={setType} />
      </Field>
      <label className="check">
        <input type="checkbox" checked={nullable} onChange={(e) => setNullable(e.target.checked)} />
        <span>Allow NULL</span>
      </label>
      {becomingRequired && (
        <Field label="Fill existing NULLs with"
               hint="A SQL expression. Existing rows need a value before the column can be required.">
          <input value={fillValue} onChange={(e) => setFillValue(e.target.value)} spellCheck={false}
                 className="mono-input" />
        </Field>
      )}
      <Field label="Default" hint="A SQL expression for new rows. Leave empty for none.">
        <input value={defaultExpr} onChange={(e) => setDefaultExpr(e.target.value)}
               placeholder="none" spellCheck={false} className="mono-input" />
      </Field>
      <FormError error={error} />
    </Modal>
  )
}

export function AddColumnForm({ table, onSubmit, onClose }: {
  table: Table; onSubmit: Submit; onClose: () => void
}) {
  const [name, setName] = useState('')
  const [type, setType] = useState('text')
  const [nullable, setNullable] = useState(true)
  const [defaultExpr, setDefaultExpr] = useState('')

  const trimmedDefault = defaultExpr.trim() === '' ? null : defaultExpr.trim()
  // A NOT NULL column with no default cannot be added to a table that has rows: there is nothing
  // to put in them. Say so before submit rather than after Postgres refuses.
  const needsDefault = !nullable && !trimmedDefault && (table.approxRows ?? 0) > 0
  const invalid = name.trim() === '' || needsDefault

  const { submit, pending, error } = useSubmit(() => onSubmit([{
    op: 'ADD_COLUMN', tableId: table.id, name: name.trim(), type, nullable, defaultExpr: trimmedDefault,
  }], `Add ${table.name}.${name.trim()}`), onClose)
  const go = () => { if (!invalid) submit() }

  return (
    <Modal title={`Add a column to ${table.name}`} onClose={onClose} onSubmit={go}
           footer={<Footer onClose={onClose} submit={go} pending={pending} disabled={invalid}
                           label="Add column" pendingLabel="Adding…" />}>
      <Field label="Name">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. currency"
               spellCheck={false} className="mono-input" />
      </Field>
      <Field label="Type"><TypeInput id="new-col-types" value={type} onChange={setType} /></Field>
      <label className="check">
        <input type="checkbox" checked={nullable} onChange={(e) => setNullable(e.target.checked)} />
        <span>Allow NULL</span>
      </label>
      <Field label="Default" hint={needsDefault
        ? `${table.name} already has rows, so a required column needs a default to fill them.`
        : 'A SQL expression. Optional.'}>
        <input value={defaultExpr} onChange={(e) => setDefaultExpr(e.target.value)} placeholder="none"
               spellCheck={false} className="mono-input" />
      </Field>
      <FormError error={error} />
    </Modal>
  )
}

export function AddIndexForm({ table, onSubmit, onClose }: {
  table: Table; onSubmit: Submit; onClose: () => void
}) {
  const [selected, setSelected] = useState<string[]>([])
  const [unique, setUnique] = useState(false)
  const [name, setName] = useState('')

  const chosen = selected.map((id) => table.columns.find((c) => c.id === id)!).filter(Boolean)
  const suggested = chosen.length ? `${table.name}_${chosen.map((c) => c.name).join('_')}_idx` : ''
  const finalName = name.trim() || suggested
  const invalid = selected.length === 0 || finalName === ''

  const toggle = (id: string) =>
    setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]))

  const { submit, pending, error } = useSubmit(() => onSubmit([{
    op: 'ADD_INDEX', tableId: table.id, name: finalName, columnIds: selected, unique, method: 'btree',
  }], `Add index ${finalName}`), onClose)
  const go = () => { if (!invalid) submit() }

  return (
    <Modal
      title={`Add an index to ${table.name}`}
      subtitle={(table.approxRows ?? 0) > 100000
        ? 'Large table: at merge time this builds CONCURRENTLY, slower but never blocking writes.'
        : undefined}
      onClose={onClose} onSubmit={go}
      footer={<Footer onClose={onClose} submit={go} pending={pending} disabled={invalid}
                      label="Add index" pendingLabel="Adding…" />}
    >
      <Field label="Columns, in order" hint="Order matters: an index on (a, b) is not one on (b, a).">
        <div className="chips">
          {table.columns.map((c) => (
            <button key={c.id} type="button" onClick={() => toggle(c.id)}
                    className={`chip ${selected.includes(c.id) ? 'chip-on' : ''}`}>
              {selected.includes(c.id) && <span className="chip-order">{selected.indexOf(c.id) + 1}</span>}
              {c.name}
            </button>
          ))}
        </div>
      </Field>
      <label className="check">
        <input type="checkbox" checked={unique} onChange={(e) => setUnique(e.target.checked)} />
        <span>Unique</span>
      </label>
      <Field label="Name">
        <input value={name} onChange={(e) => setName(e.target.value)}
               placeholder={suggested || 'index name'} spellCheck={false} className="mono-input" />
      </Field>
      <FormError error={error} />
    </Modal>
  )
}

export function CreateTableForm({ onSubmit, onClose }: { onSubmit: Submit; onClose: () => void }) {
  const [name, setName] = useState('')
  const [columns, setColumns] = useState([
    { name: 'id', type: 'bigint', nullable: false, primaryKey: true },
  ])
  const update = (i: number, patch: Partial<(typeof columns)[number]>) =>
    setColumns((cs) => cs.map((c, j) => (j === i ? { ...c, ...patch } : c)))
  const invalid = name.trim() === '' || columns.some((c) => c.name.trim() === '')

  const { submit, pending, error } = useSubmit(() => onSubmit([{
    op: 'CREATE_TABLE', name: name.trim(),
    columns: columns.map((c) => ({ name: c.name.trim(), type: c.type, nullable: c.nullable,
                                   defaultExpr: null, primaryKey: c.primaryKey })),
  }], `Create table ${name.trim()}`), onClose)
  const go = () => { if (!invalid) submit() }

  return (
    <Modal title="Create a table" onClose={onClose} onSubmit={go}
           footer={<Footer onClose={onClose} submit={go} pending={pending} disabled={invalid}
                           label="Create table" pendingLabel="Creating…" />}>
      <Field label="Table name">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. shipments"
               spellCheck={false} className="mono-input" />
      </Field>
      <span className="field-label">Columns</span>
      <div className="col-editor">
        {columns.map((c, i) => (
          <div key={i} className="col-editor-row">
            <input value={c.name} onChange={(e) => update(i, { name: e.target.value })}
                   placeholder="name" spellCheck={false} className="mono-input" />
            <TypeInput id={`new-table-type-${i}`} value={c.type} onChange={(v) => update(i, { type: v })} />
            <label className="check inline">
              <input type="checkbox" checked={c.primaryKey}
                     onChange={(e) => update(i, { primaryKey: e.target.checked, nullable: false })} />
              <span>PK</span>
            </label>
            <label className="check inline">
              <input type="checkbox" checked={c.nullable} disabled={c.primaryKey}
                     onChange={(e) => update(i, { nullable: e.target.checked })} />
              <span>null</span>
            </label>
            <button className="icon" aria-label="Remove column" disabled={columns.length === 1}
                    onClick={() => setColumns((cs) => cs.filter((_, j) => j !== i))}>&times;</button>
          </div>
        ))}
      </div>
      <div>
        <button onClick={() => setColumns((cs) => [
          ...cs, { name: '', type: 'text', nullable: true, primaryKey: false }])}>Add column</button>
      </div>
      <FormError error={error} />
    </Modal>
  )
}

/** Replaces the last prompt() in the app: branching now happens in a real dialog. */
export function NewBranchForm({ branches, onCreate, onClose }: {
  branches: Branch[]
  onCreate: (name: string, from: string) => Promise<void>
  onClose: () => void
}) {
  const [name, setName] = useState('')
  const [from, setFrom] = useState('main')
  const sources = branches.filter((b) => b.status === 'ACTIVE' || b.isMain)
  const clash = branches.some((b) => b.name === name.trim())
  const invalidName = name.trim() !== '' && !/^[A-Za-z_][A-Za-z0-9_$]*$/.test(name.trim())
  const invalid = name.trim() === '' || clash || invalidName

  const { submit, pending, error } = useSubmit(() => onCreate(name.trim(), from), onClose)
  const go = () => { if (!invalid) submit() }

  return (
    <Modal title="New branch"
           subtitle="A real Postgres schema with the same tables and a sample of the rows. Takes under a second, whatever the size of main."
           onClose={onClose} onSubmit={go}
           footer={<Footer onClose={onClose} submit={go} pending={pending} disabled={invalid}
                           label="Create branch" pendingLabel="Branching…" />}>
      <Field label="Name" hint={clash ? 'A branch with that name already exists.'
        : invalidName ? 'Letters, digits and underscores; must start with a letter or underscore.'
        : undefined}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. add_currency"
               spellCheck={false} className="mono-input" />
      </Field>
      <Field label="Branch from">
        <select value={from} onChange={(e) => setFrom(e.target.value)}>
          {sources.map((b) => <option key={b.id} value={b.name}>{b.name}</option>)}
        </select>
      </Field>
      <FormError error={error} />
    </Modal>
  )
}
