import { useMemo, useRef, useState } from 'react'
import type { Column, SchemaView, Table } from './api'
import { TableCard } from './components'
import { Kbd, useHotkey } from './ui'

/**
 * The schema browser: a table navigator, a filter, and collapsible tables.
 *
 * The first version was a single long scroll of every column of every table, which is fine for
 * four tables and useless for forty. Filtering matches table AND column names, because "where is
 * customer_id?" is the question people actually ask of a schema.
 */
export function SchemaTab({ schema, readOnly, onEditColumn, onDropColumn, onAddColumn,
                            onAddIndex, onDropIndex, onDropTable, onCreateTable }: {
  schema: SchemaView
  readOnly: boolean
  onEditColumn: (t: Table, c: Column) => void
  onDropColumn: (t: Table, c: Column) => void
  onAddColumn: (t: Table) => void
  onAddIndex: (t: Table) => void
  onDropIndex: (t: Table, indexId: string, name: string) => void
  onDropTable: (t: Table) => void
  onCreateTable: () => void
}) {
  const [query, setQuery] = useState('')
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  const searchRef = useRef<HTMLInputElement>(null)

  useHotkey('/', () => searchRef.current?.focus())

  const q = query.trim().toLowerCase()
  const visible = useMemo(() => schema.tables.flatMap((t) => {
    if (!q || t.name.toLowerCase().includes(q)) return [{ table: t, columns: t.columns }]
    const cols = t.columns.filter((c) => c.name.toLowerCase().includes(q)
      || c.type.toLowerCase().includes(q))
    return cols.length ? [{ table: t, columns: cols }] : []
  }), [schema.tables, q])

  const toggle = (id: string) => setCollapsed((s) => {
    const next = new Set(s)
    if (next.has(id)) next.delete(id); else next.add(id)
    return next
  })

  const jump = (t: Table) => {
    setCollapsed((s) => { const n = new Set(s); n.delete(t.id); return n })
    document.getElementById(`table-${t.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const allCollapsed = collapsed.size === schema.tables.length && schema.tables.length > 0

  return (
    <div className="schema-layout">
      <aside className="schema-nav" aria-label="Tables">
        <div className="nav-label">Tables · {schema.tables.length}</div>
        {schema.tables.map((t) => (
          <button key={t.id} className="nav-item" onClick={() => jump(t)}>
            <span className="nav-name">{t.name}</span>
            <span className="nav-meta">
              {t.approxRows ? compact(t.approxRows) : '0'}
            </span>
          </button>
        ))}
        {!readOnly && (
          <button className="nav-item nav-add" onClick={onCreateTable}>+ New table</button>
        )}
      </aside>

      <div className="schema-main">
        <div className="schema-toolbar">
          <div className="search">
            <span className="search-icon" aria-hidden="true">⌕</span>
            <input ref={searchRef} value={query} onChange={(e) => setQuery(e.target.value)}
                   placeholder="Filter tables and columns" aria-label="Filter tables and columns"
                   onKeyDown={(e) => { if (e.key === 'Escape') { setQuery(''); e.currentTarget.blur() } }} />
            {query ? <button className="icon" onClick={() => setQuery('')} aria-label="Clear">&times;</button>
              : <Kbd>/</Kbd>}
          </div>
          <button onClick={() => setCollapsed(allCollapsed ? new Set()
            : new Set(schema.tables.map((t) => t.id)))}>
            {allCollapsed ? 'Expand all' : 'Collapse all'}
          </button>
        </div>

        {schema.unmanaged.length > 0 && (
          <div className="banner banner-info">
            <span>
              <strong>Partially managed.</strong> {schema.unmanaged.length} object(s) SchemaSync
              does not model are present and will be left untouched:{' '}
              {schema.unmanaged.map((u) => `${u.name} (${u.kind})`).join(', ')}
            </span>
          </div>
        )}

        {visible.length === 0 && (
          <div className="empty">
            <h3>Nothing matches “{query}”</h3>
            <p>The filter checks table names, column names and column types.</p>
          </div>
        )}

        {visible.map(({ table, columns }) => (
          <TableCard
            key={table.id}
            table={table}
            columns={columns}
            filtered={columns.length !== table.columns.length}
            collapsed={collapsed.has(table.id) && !q}
            onToggle={() => toggle(table.id)}
            readOnly={readOnly}
            onEditColumn={onEditColumn}
            onDropColumn={onDropColumn}
            onAddColumn={onAddColumn}
            onAddIndex={onAddIndex}
            onDropIndex={onDropIndex}
            onDropTable={onDropTable}
          />
        ))}
      </div>
    </div>
  )
}

function compact(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${Math.round(n / 1_000)}k`
  return String(n)
}
