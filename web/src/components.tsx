import { useState } from 'react'
import type { Change, Column, Conflict, RunStep, Step, Table } from './api'

/**
 * A badge saying what a migration step does to concurrent traffic.
 *
 * This is the product, compressed into one component. "blocks nothing" versus "blocks reads and
 * writes" is the distinction a user actually needs before approving a migration, and it is the
 * thing every other schema tool leaves them to infer from the SQL.
 */
export function BlocksBadge({ blocks, verdict }: { blocks: string; verdict?: string }) {
  const label =
    blocks === 'NOTHING' ? 'blocks nothing'
    : blocks === 'WRITES' ? 'blocks writes'
    : 'blocks reads + writes'
  const tone =
    blocks === 'NOTHING' ? 'ok'
    : blocks === 'WRITES' ? 'warn'
    : verdict === 'INSTANT' ? 'muted' : 'danger'
  return <span className={`badge badge-${tone}`}>{label}</span>
}

export function VerdictBadge({ verdict }: { verdict: string }) {
  const label =
    verdict === 'INSTANT' ? 'instant'
    : verdict === 'SCAN' ? 'reads every row'
    : 'rewrites table'
  const tone = verdict === 'INSTANT' ? 'ok' : verdict === 'SCAN' ? 'warn' : 'danger'
  return <span className={`badge badge-${tone}`}>{label}</span>
}

export function TableCard({
  table, onColumnAction, onAddColumn, onAddIndex, onDropTable, readOnly,
}: {
  table: Table
  readOnly: boolean
  onColumnAction?: (column: Column, action: string) => void
  onAddColumn?: (table: Table) => void
  onAddIndex?: (table: Table) => void
  onDropTable?: (table: Table) => void
}) {
  return (
    <section className="card">
      <header className="card-head">
        <h3>{table.name}</h3>
        <span className="muted small">
          {table.approxRows != null && table.approxRows > 0
            ? `~${table.approxRows.toLocaleString()} rows`
            : 'empty'}
          {table.totalSize ? ` · ${table.totalSize}` : ''}
        </span>
        {!readOnly && (
          <span className="card-actions">
            <button onClick={() => onAddColumn?.(table)}>+ Column</button>
            <button onClick={() => onAddIndex?.(table)}>+ Index</button>
            <button className="danger" onClick={() => onDropTable?.(table)}>Drop table</button>
          </span>
        )}
      </header>

      <table className="grid">
        <tbody>
          {table.columns.map((c) => (
            <tr key={c.id}>
              <td className="col-name">
                {c.name}
                {c.primaryKey && <span className="tag">PK</span>}
              </td>
              <td className="col-type">{c.type}</td>
              <td className="col-flags muted small">
                {c.nullable ? 'null' : 'NOT NULL'}
                {c.identity ? ` · identity` : ''}
                {c.defaultExpr ? ` · default ${c.defaultExpr}` : ''}
              </td>
              {!readOnly && (
                <td className="col-actions">
                  <button onClick={() => onColumnAction?.(c, 'rename')}>Rename</button>
                  <button onClick={() => onColumnAction?.(c, 'type')}>Type</button>
                  <button onClick={() => onColumnAction?.(c, 'nullable')}>
                    {c.nullable ? 'Require' : 'Allow null'}
                  </button>
                  <button onClick={() => onColumnAction?.(c, 'default')}>Default</button>
                  <button className="danger" onClick={() => onColumnAction?.(c, 'drop')}>Drop</button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>

      {table.indexes.filter((i) => !i.constraintBacked).length > 0 && (
        <div className="sub">
          {table.indexes.filter((i) => !i.constraintBacked).map((i) => (
            <div key={i.id} className="small muted index-row">
              <span>{i.unique ? 'unique index' : 'index'} <strong>{i.name}</strong> ({i.columns.join(', ')})</span>
              {!readOnly && (
                <button className="danger" onClick={() => onColumnAction?.(
                  { id: i.id, name: i.name } as Column, 'dropIndex')}>Drop</button>
              )}
            </div>
          ))}
        </div>
      )}
    </section>
  )
}

/**
 * The diff. A rename renders as "a → b" on one line, never as a drop beside an add, because that
 * distinction is the difference between a free catalog update and destroying a column of data.
 */
export function ChangeList({ changes }: { changes: Change[] }) {
  if (changes.length === 0) {
    return <p className="muted">No changes yet. Edit a table and they will show up here.</p>
  }
  const byTable = changes.reduce<Record<string, Change[]>>((acc, c) => {
    (acc[c.table] ??= []).push(c)
    return acc
  }, {})

  return (
    <>
      {Object.entries(byTable).map(([tableName, list]) => (
        <section key={tableName} className="card">
          <header className="card-head"><h3>{tableName}</h3></header>
          <ul className="changes">
            {list.map((c, i) => (
              <li key={i} className={c.destructive ? 'destructive' : ''}>
                <span className={`dot ${c.destructive ? 'dot-danger' : 'dot-ok'}`} />
                {c.kind === 'COLUMN_RENAMED' ? (
                  <span>
                    Renamed <code>{c.from}</code> <span className="arrow">&rarr;</span> <code>{c.to}</code>
                    <span className="muted small"> (instant, no data touched)</span>
                  </span>
                ) : (
                  <span>{c.description}</span>
                )}
                {c.destructive && <span className="badge badge-danger">may lose data</span>}
              </li>
            ))}
          </ul>
        </section>
      ))}
    </>
  )
}

/** One conflict, as base / target / source with a choice. */
export function ConflictCard({
  conflict, choice, onChoose,
}: {
  conflict: Conflict
  choice: string | undefined
  onChoose: (key: string, value: string) => void
}) {
  const key = conflict.attribute ? `${conflict.stableId}:${conflict.attribute}` : conflict.stableId
  return (
    <section className={`card conflict sev-${conflict.severity.toLowerCase()}`}>
      <header className="card-head">
        <h3>{conflict.table}.{conflict.object}</h3>
        <span className={`badge badge-${conflict.severity === 'DESTRUCTIVE' ? 'danger'
          : conflict.severity === 'STRUCTURAL' ? 'warn' : 'muted'}`}>
          {conflict.severity.toLowerCase()}
        </span>
      </header>
      <p className="question">{conflict.question}</p>
      <div className="three-way">
        <div>
          <span className="small muted">Base</span>
          <code>{conflict.base ?? '—'}</code>
        </div>
        <div className={choice === 'OURS' || !choice ? 'chosen' : ''}>
          <span className="small muted">Target (ours)</span>
          <code>{conflict.ours ?? '—'}</code>
          <button onClick={() => onChoose(key, 'OURS')}>Use target</button>
        </div>
        <div className={choice === 'THEIRS' ? 'chosen' : ''}>
          <span className="small muted">Source (theirs)</span>
          <code>{conflict.theirs ?? '—'}</code>
          <button onClick={() => onChoose(key, 'THEIRS')}>Use source</button>
        </div>
      </div>
    </section>
  )
}

/** The plan preview: what will run, what it costs, and why. */
export function PlanSteps({ steps }: { steps: Step[] }) {
  const [openSql, setOpenSql] = useState<number | null>(null)
  return (
    <ol className="plan">
      {steps.map((s) => (
        <li key={s.seq} className={s.pointOfNoReturn ? 'pnr' : ''}>
          <div className="plan-head">
            <span className="plan-desc">{s.description}</span>
            <VerdictBadge verdict={s.verdict} />
            <BlocksBadge blocks={s.blocks} verdict={s.verdict} />
            {s.pointOfNoReturn && <span className="badge badge-danger">irreversible</span>}
          </div>
          <div className="small muted">{s.rationale}</div>
          {s.sql && (
            <>
              <button className="link" onClick={() => setOpenSql(openSql === s.seq ? null : s.seq)}>
                {openSql === s.seq ? 'hide SQL' : 'show SQL'}
              </button>
              {openSql === s.seq && <pre className="sql">{s.sql}</pre>}
            </>
          )}
        </li>
      ))}
    </ol>
  )
}

/** Live migration progress, including real backfill counts. */
export function RunProgress({ steps }: { steps: RunStep[] }) {
  return (
    <ol className="plan">
      {steps.map((s) => {
        const pct = s.rowsEstimated && s.rowsEstimated > 0
          ? Math.min(100, Math.round(((s.rowsDone ?? 0) / s.rowsEstimated) * 100))
          : null
        return (
          <li key={s.seq} className={`status-${s.status.toLowerCase()}`}>
            <div className="plan-head">
              <span className={`state state-${s.status.toLowerCase()}`}>
                {s.status === 'SUCCEEDED' ? '✓' : s.status === 'RUNNING' ? '…'
                  : s.status === 'FAILED' ? '✗' : '·'}
              </span>
              <span className="plan-desc">{s.description}</span>
              <BlocksBadge blocks={s.blocks} />
              {s.lockWaitMs != null && s.lockWaitMs > 0 && (
                <span className="small muted">waited {s.lockWaitMs}ms for the lock</span>
              )}
            </div>
            {pct != null && s.status !== 'PENDING' && (
              <div className="progress">
                <div className="progress-bar" style={{ width: `${pct}%` }} />
                <span className="small muted">
                  {(s.rowsDone ?? 0).toLocaleString()} / {(s.rowsEstimated ?? 0).toLocaleString()} rows
                  {s.batchSize ? ` · batch ${s.batchSize.toLocaleString()}` : ''}
                </span>
              </div>
            )}
            {s.error && <pre className="error-box">{s.error}</pre>}
          </li>
        )
      })}
    </ol>
  )
}
