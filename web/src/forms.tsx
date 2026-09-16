import { useState } from 'react'
import type { Column, Operation, Table } from './api'
import { Field, Modal, TypeInput } from './ui'

/**
 * Edits every attribute of a column in one dialog, and emits only what actually changed.
 *
 * The gain over asking one question at a time is not just fewer clicks: renaming and retyping in
 * one form produces ONE commit containing both operations, which is what the merge engine is built
 * for. Four sequential prompts produced four commits describing a change nobody made in four steps.
 */
export function ColumnEditor({
  table, column, onSubmit, onClose,
}: {
  table: Table
  column: Column
  onSubmit: (ops: Operation[], message: string) => void
  onClose: () => void
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
    ops.push({ op: 'RENAME_COLUMN', tableId: table.id, columnId: column.id, newName: name })
    parts.push(`rename to ${name}`)
  }
  if (type !== column.type) {
    ops.push({ op: 'CHANGE_COLUMN_TYPE', tableId: table.id, columnId: column.id, newType: type })
    parts.push(`retype to ${type}`)
  }
  if (nullable !== column.nullable) {
    ops.push({
      op: 'SET_COLUMN_NULLABLE', tableId: table.id, columnId: column.id,
      nullable, fillValue: becomingRequired ? fillValue : null,
    })
    parts.push(nullable ? 'allow NULL' : 'require a value')
  }
  if (trimmedDefault !== (column.defaultExpr ?? null)) {
    ops.push({
      op: 'SET_COLUMN_DEFAULT', tableId: table.id, columnId: column.id, defaultExpr: trimmedDefault,
    })
    parts.push(trimmedDefault ? `default ${trimmedDefault}` : 'remove default')
  }

  return (
    <Modal
      title={`Edit ${table.name}.${column.name}`}
      subtitle="Changes are applied to this branch immediately, and to main only when you merge."
      onClose={onClose}
      footer={
        <>
          <span className="muted small">
            {ops.length === 0 ? 'No changes' : `${ops.length} change${ops.length === 1 ? '' : 's'}`}
          </span>
          <button onClick={onClose}>Cancel</button>
          <button
            className="primary"
            disabled={ops.length === 0 || name.trim() === ''}
            onClick={() => { onSubmit(ops, `${column.name}: ${parts.join(', ')}`); onClose() }}
          >
            Save
          </button>
        </>
      }
    >
      <Field label="Name">
        <input value={name} onChange={(e) => setName(e.target.value)} spellCheck={false} />
      </Field>

      <Field
        label="Type"
        hint={type !== column.type
          ? 'On a large table this is applied online when you merge, without blocking reads or writes.'
          : undefined}
      >
        <TypeInput id="col-types" value={type} onChange={setType} />
      </Field>

      <label className="check">
        <input type="checkbox" checked={nullable} onChange={(e) => setNullable(e.target.checked)} />
        <span>Allow NULL</span>
      </label>

      {becomingRequired && (
        <Field
          label="Fill existing NULLs with"
          hint="A SQL expression. Existing rows need a value before the column can be required."
        >
          <input value={fillValue} onChange={(e) => setFillValue(e.target.value)} spellCheck={false} />
        </Field>
      )}

      <Field label="Default" hint="A SQL expression, applied to new rows only. Leave empty for none.">
        <input value={defaultExpr} onChange={(e) => setDefaultExpr(e.target.value)}
               placeholder="none" spellCheck={false} />
      </Field>
    </Modal>
  )
}

export function AddColumnForm({
  table, onSubmit, onClose,
}: {
  table: Table
  onSubmit: (ops: Operation[], message: string) => void
  onClose: () => void
}) {
  const [name, setName] = useState('')
  const [type, setType] = useState('text')
  const [nullable, setNullable] = useState(true)
  const [defaultExpr, setDefaultExpr] = useState('')

  const trimmedDefault = defaultExpr.trim() === '' ? null : defaultExpr.trim()
  // A NOT NULL column with no default cannot be added to a table that already has rows: there is
  // nothing to put in them. Say so before the user hits submit rather than after.
  const needsDefault = !nullable && !trimmedDefault && (table.approxRows ?? 0) > 0

  return (
    <Modal
      title={`Add a column to ${table.name}`}
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>Cancel</button>
          <button
            className="primary"
            disabled={name.trim() === '' || needsDefault}
            onClick={() => {
              onSubmit([{
                op: 'ADD_COLUMN', tableId: table.id, name: name.trim(), type,
                nullable, defaultExpr: trimmedDefault,
              }], `Add ${table.name}.${name.trim()}`)
              onClose()
            }}
          >
            Add column
          </button>
        </>
      }
    >
      <Field label="Name">
        <input value={name} onChange={(e) => setName(e.target.value)}
               placeholder="e.g. currency" spellCheck={false} />
      </Field>
      <Field label="Type">
        <TypeInput id="new-col-types" value={type} onChange={setType} />
      </Field>
      <label className="check">
        <input type="checkbox" checked={nullable} onChange={(e) => setNullable(e.target.checked)} />
        <span>Allow NULL</span>
      </label>
      <Field
        label="Default"
        hint={needsDefault
          ? `${table.name} already has rows, so a required column needs a default to fill them.`
          : 'A SQL expression. Optional.'}
      >
        <input value={defaultExpr} onChange={(e) => setDefaultExpr(e.target.value)}
               placeholder="none" spellCheck={false} />
      </Field>
      {needsDefault && <p className="warn-text">Give this column a default, or allow NULL.</p>}
    </Modal>
  )
}

export function AddIndexForm({
  table, onSubmit, onClose,
}: {
  table: Table
  onSubmit: (ops: Operation[], message: string) => void
  onClose: () => void
}) {
  const [selected, setSelected] = useState<string[]>([])
  const [unique, setUnique] = useState(false)
  const [name, setName] = useState('')

  const chosen = table.columns.filter((c) => selected.includes(c.id))
  const suggested = chosen.length
    ? `${table.name}_${chosen.map((c) => c.name).join('_')}_idx`
    : ''
  const finalName = name.trim() || suggested

  const toggle = (id: string) =>
    setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]))

  return (
    <Modal
      title={`Add an index to ${table.name}`}
      subtitle={(table.approxRows ?? 0) > 100000
        ? 'This table is large, so the index will be built concurrently when you merge -- slower, but it never blocks writes.'
        : undefined}
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>Cancel</button>
          <button
            className="primary"
            disabled={selected.length === 0 || finalName === ''}
            onClick={() => {
              onSubmit([{
                op: 'ADD_INDEX', tableId: table.id, name: finalName,
                columnIds: selected, unique, method: 'btree',
              }], `Add index ${finalName}`)
              onClose()
            }}
          >
            Add index
          </button>
        </>
      }
    >
      <Field label="Columns" hint="Order matters: an index on (a, b) is not the same as (b, a).">
        <div className="chips">
          {table.columns.map((c) => (
            <button
              key={c.id}
              type="button"
              className={`chip ${selected.includes(c.id) ? 'chip-on' : ''}`}
              onClick={() => toggle(c.id)}
            >
              {selected.includes(c.id) && (
                <span className="chip-order">{selected.indexOf(c.id) + 1}</span>
              )}
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
               placeholder={suggested || 'index name'} spellCheck={false} />
      </Field>
    </Modal>
  )
}

export function CreateTableForm({
  onSubmit, onClose,
}: {
  onSubmit: (ops: Operation[], message: string) => void
  onClose: () => void
}) {
  const [name, setName] = useState('')
  const [columns, setColumns] = useState([
    { name: 'id', type: 'bigint', nullable: false, primaryKey: true },
  ])

  const update = (i: number, patch: Partial<(typeof columns)[number]>) =>
    setColumns((cs) => cs.map((c, j) => (j === i ? { ...c, ...patch } : c)))

  return (
    <Modal
      title="Create a table"
      onClose={onClose}
      footer={
        <>
          <button onClick={onClose}>Cancel</button>
          <button
            className="primary"
            disabled={name.trim() === '' || columns.some((c) => c.name.trim() === '')}
            onClick={() => {
              onSubmit([{
                op: 'CREATE_TABLE', name: name.trim(),
                columns: columns.map((c) => ({
                  name: c.name.trim(), type: c.type,
                  nullable: c.nullable, defaultExpr: null, primaryKey: c.primaryKey,
                })),
              }], `Create table ${name.trim()}`)
              onClose()
            }}
          >
            Create table
          </button>
        </>
      }
    >
      <Field label="Table name">
        <input value={name} onChange={(e) => setName(e.target.value)}
               placeholder="e.g. shipments" spellCheck={false} />
      </Field>

      <span className="field-label">Columns</span>
      <div className="col-editor">
        {columns.map((c, i) => (
          <div key={i} className="col-editor-row">
            <input
              value={c.name}
              onChange={(e) => update(i, { name: e.target.value })}
              placeholder="name" spellCheck={false}
            />
            <TypeInput id={`new-table-type-${i}`} value={c.type}
                       onChange={(v) => update(i, { type: v })} />
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
            <button className="icon" aria-label="Remove column"
                    disabled={columns.length === 1}
                    onClick={() => setColumns((cs) => cs.filter((_, j) => j !== i))}>&times;</button>
          </div>
        ))}
      </div>
      <button onClick={() => setColumns((cs) => [
        ...cs, { name: '', type: 'text', nullable: true, primaryKey: false },
      ])}>+ Add column</button>
    </Modal>
  )
}
