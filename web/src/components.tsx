import { useState } from 'react'
import type { Change, Column, Conflict, RunStep, Step, Table } from './api'
import { CopyButton } from './ui'

/**
 * What a step does to concurrent traffic -- the product compressed into one badge. "blocks
 * nothing" versus "blocks reads and writes" is what a user needs before approving a migration.
 */
export function BlocksBadge({ blocks, verdict }: { blocks: string; verdict?: string }) {
  const label = blocks === 'NOTHING' ? 'blocks nothing'
    : blocks === 'WRITES' ? 'blocks writes' : 'blocks reads + writes'
  const tone = blocks === 'NOTHING' ? 'ok'
    : blocks === 'WRITES' ? 'warn'
    : verdict === 'INSTANT' ? 'muted' : 'danger'
  return <span className={`badge badge-${tone}`}>{label}</span>
}

export function VerdictBadge({ verdict }: { verdict: string }) {
  const label = verdict === 'INSTANT' ? 'instant' : verdict === 'SCAN' ? 'reads every row' : 'rewrites table'
  const tone = verdict === 'INSTANT' ? 'ok' : verdict === 'SCAN' ? 'warn' : 'danger'
  return <span className={`badge badge-${tone}`}>{label}</span>
}

export function TableCard({
  table, columns, filtered, collapsed, onToggle, readOnly,
  onEditColumn, onDropColumn, onAddColumn, onAddIndex, onDropIndex, onDropTable,
}: {
  table: Table
  columns: Column[]
  filtered: boolean
  collapsed: boolean
  onToggle: () => void
  readOnly: boolean
  onEditColumn: (t: Table, c: Column) => void
  onDropColumn: (t: Table, c: Column) => void
  onAddColumn: (t: Table) => void
  onAddIndex: (t: Table) => void
  onDropIndex: (t: Table, indexId: string, name: string) => void
  onDropTable: (t: Table) => void
}) {
  const indexes = table.indexes.filter((i) => !i.constraintBacked)
  return (
    <section className={`card ${collapsed ? 'collapsed' : ''}`} id={`table-${table.id}`}>
      <header className="card-head">
        <button className="collapse" onClick={onToggle} aria-expanded={!collapsed}
                aria-label={collapsed ? `Expand ${table.name}` : `Collapse ${table.name}`}>
          <span className="chevron-r" aria-hidden="true">›</span>
        </button>
        <h3>
          {table.name}
          <span className="muted small">
            {table.approxRows != null && table.approxRows > 0
              ? `${table.approxRows.toLocaleString()} rows` : 'empty'}
            {table.totalSize ? ` · ${table.totalSize}` : ''}
            {collapsed ? ` · ${table.columns.length} columns` : ''}
            {filtered ? ` · showing ${columns.length} of ${table.columns.length}` : ''}
          </span>
        </h3>
        {!readOnly && !collapsed && (
          <div className="card-actions">
            <button onClick={() => onAddColumn(table)}>Add column</button>
            <button onClick={() => onAddIndex(table)}>Add index</button>
            <button className="ghost-danger" onClick={() => onDropTable(table)}>Drop</button>
          </div>
        )}
      </header>

      {!collapsed && (
        <>
          <div className="rows">
            {columns.map((c) => (
              <div className="row" key={c.id}>
                <div className="row-name">
                  <span className="n">{c.name}</span>
                  {c.primaryKey && <span className="pk">PK</span>}
                </div>
                <div className="row-type mono">{c.type}</div>
                <div className="row-meta">
                  {c.nullable ? 'nullable' : 'not null'}
                  {c.identity ? ' · identity' : ''}
                  {c.defaultExpr ? ` · = ${c.defaultExpr}` : ''}
                </div>
                {!readOnly && (
                  <div className="row-actions">
                    <button onClick={() => onEditColumn(table, c)}>Edit</button>
                    <button className="ghost-danger" onClick={() => onDropColumn(table, c)}>Drop</button>
                  </div>
                )}
              </div>
            ))}
          </div>
          {indexes.length > 0 && !filtered && (
            <div className="card-sub">
              {indexes.map((i) => (
                <div className="sub-row" key={i.id}>
                  <span>{i.unique ? 'unique index' : 'index'} <strong>{i.name}</strong> ({i.columns.join(', ')})</span>
                  {!readOnly && (
                    <button className="ghost-danger" onClick={() => onDropIndex(table, i.id, i.name)}>Drop</button>
                  )}
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </section>
  )
}

/**
 * The diff. A rename renders as one line carrying both names -- never as a drop beside an add,
 * which is the difference between a free catalog update and losing a column of data.
 */
export function ChangeList({ changes }: { changes: Change[] }) {
  if (changes.length === 0) {
    return (
      <div className="empty">
        <h3>No changes yet</h3>
        <p>Edit a table on the Schema tab. Whatever you change shows up here, compared against the
           point this branch forked from.</p>
      </div>
    )
  }
  const byTable = changes.reduce<Record<string, Change[]>>((acc, c) => {
    (acc[c.table] ??= []).push(c); return acc
  }, {})
  const destructive = changes.filter((c) => c.destructive).length

  return (
    <>
      <div className="summary-strip">
        <span><strong>{changes.length}</strong> change{changes.length === 1 ? '' : 's'}</span>
        <span><strong>{Object.keys(byTable).length}</strong> table{Object.keys(byTable).length === 1 ? '' : 's'}</span>
        {destructive > 0
          ? <span className="danger-text"><strong>{destructive}</strong> may lose data</span>
          : <span className="ok-text">nothing destructive</span>}
      </div>
      {Object.entries(byTable).map(([name, list]) => (
        <section className="card" key={name}>
          <header className="card-head"><h3>{name}</h3></header>
          <ul className="changes">
            {list.map((c, i) => (
              <li key={i}>
                <span className={`dot ${c.destructive ? 'dot-danger' : 'dot-ok'}`} />
                {c.kind === 'COLUMN_RENAMED' ? (
                  <span>
                    <code className="rename-from">{c.from}</code>
                    <span className="arrow">→</span>
                    <code>{c.to}</code>
                    <span className="muted small"> renamed, no data touched</span>
                  </span>
                ) : <span>{c.description}</span>}
                {c.destructive && <span className="badge badge-danger">may lose data</span>}
              </li>
            ))}
          </ul>
        </section>
      ))}
    </>
  )
}

export function ConflictCard({ conflict, choice, onChoose }: {
  conflict: Conflict
  choice: string | undefined
  onChoose: (key: string, value: string) => void
}) {
  const key = conflict.attribute ? `${conflict.stableId}:${conflict.attribute}` : conflict.stableId
  const tone = conflict.severity === 'DESTRUCTIVE' ? 'danger'
    : conflict.severity === 'STRUCTURAL' ? 'warn' : 'muted'
  return (
    <section className={`card conflict sev-${conflict.severity.toLowerCase()} ${conflict.answered ? 'answered' : ''}`}>
      <header className="card-head">
        <h3>{conflict.table}.{conflict.object}{conflict.attribute ? ` · ${conflict.attribute}` : ''}</h3>
        <span className={`badge badge-${tone}`}>{conflict.severity.toLowerCase()}</span>
        {conflict.answered && <span className="badge badge-ok">answered</span>}
      </header>
      <p className="question">{conflict.question}</p>
      <div className="three-way">
        <div className="side">
          <span className="side-label">Base</span>
          <span className="side-value">{conflict.base ?? '—'}</span>
        </div>
        <button className={`side side-pick ${choice === 'OURS' ? 'chosen' : ''}`}
                onClick={() => onChoose(key, 'OURS')} aria-pressed={choice === 'OURS'}>
          <span className="side-label">Keep target</span>
          <span className="side-value">{conflict.ours ?? '—'}</span>
        </button>
        <button className={`side side-pick ${choice === 'THEIRS' ? 'chosen' : ''}`}
                onClick={() => onChoose(key, 'THEIRS')} aria-pressed={choice === 'THEIRS'}>
          <span className="side-label">Take this branch</span>
          <span className="side-value">{conflict.theirs ?? '—'}</span>
        </button>
      </div>
    </section>
  )
}

export function PlanSteps({ steps }: { steps: Step[] }) {
  const [open, setOpen] = useState<number | null>(null)
  return (
    <section className="card">
      <ol className="plan">
        {steps.map((s) => (
          <li key={s.seq} className={s.pointOfNoReturn ? 'pnr' : ''}>
            <div className="plan-head">
              <span className="plan-desc">{s.description}</span>
              <VerdictBadge verdict={s.verdict} />
              <BlocksBadge blocks={s.blocks} verdict={s.verdict} />
              {s.pointOfNoReturn && <span className="badge badge-danger">irreversible</span>}
            </div>
            <div className="plan-why">{s.rationale}</div>
            {s.sql && (
              <div className="plan-links">
                <button className="link" onClick={() => setOpen(open === s.seq ? null : s.seq)}>
                  {open === s.seq ? 'Hide SQL' : 'Show SQL'}
                </button>
                <CopyButton text={s.sql} label="Copy SQL" />
              </div>
            )}
            {open === s.seq && s.sql && <pre className="sql">{s.sql}</pre>}
          </li>
        ))}
      </ol>
    </section>
  )
}

export function RunProgress({ steps, status }: { steps: RunStep[]; status: string }) {
  const done = steps.filter((s) => s.status === 'SUCCEEDED').length
  const current = steps.find((s) => s.status === 'RUNNING')
  return (
    <>
      <div className="run-overview">
        <div className="run-overview-head">
          <span>
            {status === 'SUCCEEDED' ? 'All steps complete'
              : status === 'FAILED' ? 'Stopped'
              : current ? `Step ${current.seq + 1} of ${steps.length}: ${current.description}`
              : 'Starting…'}
          </span>
          <span className="muted small">{done}/{steps.length}</span>
        </div>
        <div className="progress-track">
          <div className={`progress-bar ${status === 'FAILED' ? 'failed' : ''}`}
               style={{ width: `${steps.length ? (done / steps.length) * 100 : 0}%` }} />
        </div>
      </div>
      <section className="card">
        <ol className="plan">
          {steps.map((s) => {
            const pct = s.rowsEstimated && s.rowsEstimated > 0
              ? Math.min(100, Math.round(((s.rowsDone ?? 0) / s.rowsEstimated) * 100)) : null
            return (
              <li key={s.seq} className={`status-${s.status.toLowerCase()}`}>
                <div className="plan-head">
                  <span className="plan-desc">{s.description}</span>
                  <BlocksBadge blocks={s.blocks} />
                  {s.lockWaitMs != null && s.lockWaitMs > 0 && (
                    <span className="muted small">waited {s.lockWaitMs}ms for the lock</span>
                  )}
                </div>
                {pct != null && s.status !== 'PENDING' && (
                  <div className="progress">
                    <div className="progress-track"><div className="progress-bar" style={{ width: `${pct}%` }} /></div>
                    <div className="progress-label">
                      {(s.rowsDone ?? 0).toLocaleString()} of {(s.rowsEstimated ?? 0).toLocaleString()} rows
                      {s.batchSize ? ` · batches of ${s.batchSize.toLocaleString()}` : ''}
                    </div>
                  </div>
                )}
                {s.error && <pre className="error-box">{s.error}</pre>}
              </li>
            )
          })}
        </ol>
      </section>
    </>
  )
}
