import { useCallback, useEffect, useRef, useState } from 'react'
import {
  api, type Branch, type Column, type Commit, type Diff, type MergePreview,
  type Operation, type Project, type Run, type SchemaView, type Table,
} from './api'
import { ChangeList, ConflictCard, PlanSteps, RunProgress, TableCard } from './components'

type Tab = 'schema' | 'changes' | 'merge' | 'history'

const initialHash = new URLSearchParams(
  typeof location === 'undefined' ? '' : location.hash.slice(1))

export function App() {
  const [project, setProject] = useState<Project | null>(null)
  const [branches, setBranches] = useState<Branch[]>([])
  const [current, setCurrent] = useState<Branch | null>(null)
  // Tab and branch live in the URL hash, so a refresh keeps your place and a link to a specific
  // merge is shareable -- which matters when the thing you want a second opinion on is a plan
  // that is about to rewrite a production table.
  const [tab, setTab] = useState<Tab>(() => {
    const t = initialHash.get('tab')
    return (t === 'changes' || t === 'merge' || t === 'history') ? t : 'schema'
  })
  const [hydrated, setHydrated] = useState(false)

  const [schema, setSchema] = useState<SchemaView | null>(null)
  const [diff, setDiff] = useState<Diff | null>(null)
  const [history, setHistory] = useState<Commit[]>([])
  const [preview, setPreview] = useState<MergePreview | null>(null)
  const [resolutions, setResolutions] = useState<Record<string, string>>({})
  const [run, setRun] = useState<Run | null>(null)

  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const pollRef = useRef<number | null>(null)

  const guard = useCallback(async (fn: () => Promise<void>) => {
    setError(null)
    setBusy(true)
    try {
      await fn()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }, [])

  useEffect(() => {
    guard(async () => {
      const projects = await api.projects()
      if (projects.length === 0) return
      setProject(projects[0])
      const bs = await api.branches(projects[0].id)
      setBranches(bs)
      const wanted = initialHash.get('branch')
      const requested = wanted ? bs.find((b) => b.id === wanted) : undefined
      if (requested) {
        setCurrent(requested)
        setHydrated(true)
        return
      }
      // Prefer a branch that can actually be edited: landing on main (read-only) or on a
      // already-merged branch makes the first click fail for no reason.
      setCurrent(bs.find((b) => !b.isMain && b.status === 'ACTIVE') ?? bs[0] ?? null)
      setHydrated(true)
    })
  }, [guard])

  const loadBranch = useCallback((branch: Branch) => guard(async () => {
    setCurrent(branch)
    setPreview(null)
    setRun(null)
    setResolutions({})
    setSchema(await api.schema(branch.id))
    setDiff(await api.diff(branch.id))
  }), [guard])

  useEffect(() => {
    if (current) loadBranch(current)
    // Intentionally only on id: loadBranch identity changes every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.id])

  const reload = useCallback(() => guard(async () => {
    if (!current || !project) return
    setSchema(await api.schema(current.id))
    setDiff(await api.diff(current.id))
    setBranches(await api.branches(project.id))
    if (tab === 'history') setHistory(await api.history(current.id))
  }), [current, project, tab, guard])

  const applyOps = (ops: Operation[], message: string) => guard(async () => {
    if (!current) return
    await api.applyOps(current.id, ops, message)
    setSchema(await api.schema(current.id))
    setDiff(await api.diff(current.id))
    setPreview(null)
  })

  // ----- branch editing -------------------------------------------------

  const onColumnAction = (table: Table, column: Column, action: string) => {
    if (action === 'rename') {
      const name = prompt(`Rename ${column.name} to:`, column.name)
      if (!name || name === column.name) return
      applyOps([{ op: 'RENAME_COLUMN', tableId: table.id, columnId: column.id, newName: name }],
        `Rename ${column.name} to ${name}`)
    } else if (action === 'type') {
      const type = prompt(`Change ${column.name} from ${column.type} to:`, column.type)
      if (!type || type === column.type) return
      applyOps([{ op: 'CHANGE_COLUMN_TYPE', tableId: table.id, columnId: column.id, newType: type }],
        `Change ${column.name} to ${type}`)
    } else if (action === 'nullable') {
      const makeNullable = !column.nullable
      const fill = makeNullable ? null
        : prompt(`Existing NULLs in ${column.name} need a value. SQL expression:`, `''`)
      if (!makeNullable && fill === null) return
      applyOps([{
        op: 'SET_COLUMN_NULLABLE', tableId: table.id, columnId: column.id,
        nullable: makeNullable, fillValue: fill,
      }], `${makeNullable ? 'Allow NULL in' : 'Require'} ${column.name}`)
    } else if (action === 'default') {
      const value = prompt(
        `Default for ${column.name} (SQL expression, empty to remove):`, column.defaultExpr ?? '')
      if (value === null) return
      applyOps([{
        op: 'SET_COLUMN_DEFAULT', tableId: table.id, columnId: column.id,
        defaultExpr: value.trim() === '' ? null : value,
      }], `Set default for ${column.name}`)
    } else if (action === 'drop') {
      if (!confirm(`Drop column ${column.name}? On merge this permanently deletes its data.`)) return
      applyOps([{ op: 'DROP_COLUMN', tableId: table.id, columnId: column.id }],
        `Drop ${column.name}`)
    } else if (action === 'dropIndex') {
      applyOps([{ op: 'DROP_INDEX', tableId: table.id, indexId: column.id }],
        `Drop index ${column.name}`)
    }
  }

  const onAddColumn = (table: Table) => {
    const name = prompt(`New column on ${table.name} — name:`)
    if (!name) return
    const type = prompt('Type:', 'text')
    if (!type) return
    const nullable = confirm('Allow NULL?\n\nOK = nullable, Cancel = NOT NULL')
    const defaultExpr = prompt('Default (SQL expression, empty for none):', nullable ? '' : `''`)
    applyOps([{
      op: 'ADD_COLUMN', tableId: table.id, name, type, nullable,
      defaultExpr: defaultExpr?.trim() ? defaultExpr : null,
    }], `Add ${name}`)
  }

  const onAddIndex = (table: Table) => {
    const cols = prompt(
      `Index on ${table.name} — columns (comma separated):\n\n${table.columns.map(c => c.name).join(', ')}`)
    if (!cols) return
    const names = cols.split(',').map((s) => s.trim()).filter(Boolean)
    const ids = names.map((n) => table.columns.find((c) => c.name === n)?.id).filter(Boolean)
    if (ids.length !== names.length) {
      setError(`Unknown column in "${cols}"`)
      return
    }
    const name = prompt('Index name:', `${table.name}_${names.join('_')}_idx`)
    if (!name) return
    const unique = confirm('Unique index?\n\nOK = unique, Cancel = regular')
    applyOps([{ op: 'ADD_INDEX', tableId: table.id, name, columnIds: ids, unique, method: 'btree' }],
      `Add index ${name}`)
  }

  const onDropTable = (table: Table) => {
    if (!confirm(`Drop table ${table.name}? On merge this permanently deletes all its rows.`)) return
    applyOps([{ op: 'DROP_TABLE', tableId: table.id }], `Drop table ${table.name}`)
  }

  const onCreateTable = () => {
    const name = prompt('New table name:')
    if (!name) return
    applyOps([{
      op: 'CREATE_TABLE', name,
      columns: [{ name: 'id', type: 'bigint', nullable: false, defaultExpr: null, primaryKey: true }],
    }], `Create table ${name}`)
  }

  // ----- branches -------------------------------------------------------

  const onNewBranch = () => guard(async () => {
    if (!project) return
    const name = prompt('Branch name:', 'my_change')
    if (!name) return
    const created = await api.createBranch(project.id, name)
    setBranches(await api.branches(project.id))
    setCurrent(created)
  })

  const onDeleteBranch = () => guard(async () => {
    if (!current || !project || current.isMain) return
    if (!confirm(`Delete branch ${current.name}? Its schema is dropped.`)) return
    await api.deleteBranch(current.id)
    const bs = await api.branches(project.id)
    setBranches(bs)
    setCurrent(bs[0] ?? null)
  })

  const onRefresh = () => guard(async () => {
    if (!current) return
    await api.refresh(current.id)
    await reload()
  })

  // ----- merge ----------------------------------------------------------

  const loadPreview = (next = resolutions) => guard(async () => {
    if (!current) return
    setPreview(await api.mergePreview(current.id, next))
  })

  const onChoose = (key: string, value: string) => {
    const next = { ...resolutions, [key]: value }
    setResolutions(next)
    loadPreview(next)
  }

  const onApplyMerge = () => guard(async () => {
    if (!current) return
    const started = await api.mergeApply(current.id, resolutions)
    setRun(await api.run(started.runId))
    // Poll rather than stream: a plan is tens of rows, so re-reading the whole state is cheap and
    // a dropped connection repairs itself on the next tick with no replay logic.
    if (pollRef.current) window.clearInterval(pollRef.current)
    pollRef.current = window.setInterval(async () => {
      try {
        const r = await api.run(started.runId)
        setRun(r)
        if (r.status === 'SUCCEEDED' || r.status === 'FAILED') {
          if (pollRef.current) window.clearInterval(pollRef.current)
          pollRef.current = null
          await reload()
        }
      } catch { /* keep polling; a transient failure is not fatal */ }
    }, 700)
  })

  useEffect(() => () => { if (pollRef.current) window.clearInterval(pollRef.current) }, [])

  useEffect(() => {
    // Only start writing the hash once the initial selection is settled, or this would clobber
    // the branch the incoming URL asked for.
    if (!hydrated) return
    const params = new URLSearchParams()
    params.set('tab', tab)
    if (current) params.set('branch', current.id)
    window.history.replaceState(null, '', `#${params.toString()}`)
  }, [hydrated, tab, current?.id])

  useEffect(() => {
    if (tab === 'merge' && current && !current.isMain && !preview && !run) loadPreview()
    if (tab === 'history' && current) guard(async () => setHistory(await api.history(current.id)))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, current?.id])

  // ----- render ---------------------------------------------------------

  if (!project) {
    return (
      <main className="shell">
        <h1>SchemaSync</h1>
        {error ? <p className="error">{error}</p> : (
          <p className="muted">
            No project yet. Run <code>./scripts/seed.sh</code> to create the demo schema, then
            restart the API.
          </p>
        )}
      </main>
    )
  }

  const changeCount = diff?.changes.length ?? 0

  return (
    <main className="shell">
      <header className="top">
        <div>
          <h1>SchemaSync</h1>
          <p className="tagline">
            Version control for <code>{project.mainSchema}</code>
          </p>
        </div>
        <div className="branch-bar">
          <select
            value={current?.id ?? ''}
            onChange={(e) => {
              const b = branches.find((x) => x.id === e.target.value)
              if (b) setCurrent(b)
            }}
          >
            {branches.map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
                {b.isMain ? ' (main)' : ''}
                {b.status === 'DRIFTED' ? ' — drifted' : ''}
                {b.status === 'MERGED' ? ' — merged' : ''}
              </option>
            ))}
          </select>
          <button onClick={onNewBranch}>New branch</button>
          {current && !current.isMain && (
            <button className="danger" onClick={onDeleteBranch}>Delete</button>
          )}
        </div>
      </header>

      {error && (
        <div className="banner banner-error">
          <strong>{error}</strong>
          <button className="link" onClick={() => setError(null)}>dismiss</button>
        </div>
      )}

      {diff?.drifted && (
        <div className="banner banner-warn">
          <strong>This branch has drifted.</strong> Its schema was changed outside SchemaSync, so
          rename tracking is no longer reliable and merging is blocked.
          <button onClick={onRefresh}>Re-import the live schema</button>
        </div>
      )}

      {current?.isMain && (
        <div className="banner banner-info">
          <strong>This is main.</strong> It cannot be edited directly — that is the one path that
          gets safety analysis and an online migration. Branch it and merge the change back.
        </div>
      )}

      <nav className="tabs">
        <button className={tab === 'schema' ? 'on' : ''} onClick={() => setTab('schema')}>Schema</button>
        <button className={tab === 'changes' ? 'on' : ''} onClick={() => setTab('changes')}>
          Changes{changeCount > 0 ? ` (${changeCount})` : ''}
        </button>
        {!current?.isMain && (
          <button className={tab === 'merge' ? 'on' : ''} onClick={() => setTab('merge')}>Merge</button>
        )}
        <button className={tab === 'history' ? 'on' : ''} onClick={() => setTab('history')}>History</button>
        {busy && <span className="muted small spinner">working…</span>}
      </nav>

      {tab === 'schema' && schema && (
        <>
          {schema.unmanaged.length > 0 && (
            <div className="banner banner-info">
              <strong>Partially managed.</strong> {schema.unmanaged.length} object(s) SchemaSync
              does not model are present and will be left alone:{' '}
              {schema.unmanaged.map((u) => `${u.name} (${u.kind})`).join(', ')}
            </div>
          )}
          {!current?.isMain && (
            <div className="toolbar">
              <button onClick={onCreateTable}>+ New table</button>
            </div>
          )}
          {schema.tables.map((t) => (
            <TableCard
              key={t.id}
              table={t}
              readOnly={!!current?.isMain}
              onColumnAction={(c, a) => onColumnAction(t, c, a)}
              onAddColumn={onAddColumn}
              onAddIndex={onAddIndex}
              onDropTable={onDropTable}
            />
          ))}
        </>
      )}

      {tab === 'changes' && diff && (
        <>
          <p className="muted small">
            Compared against the point this branch forked from. This answers <em>what is
            different</em>; the History tab answers <em>how it got there</em>.
          </p>
          <ChangeList changes={diff.changes} />
        </>
      )}

      {tab === 'merge' && !run && preview && (
        <>
          <div className="merge-head">
            <h2>{preview.source} <span className="arrow">&rarr;</span> {preview.target}</h2>
            <span className={`badge badge-${preview.mode === 'ATOMIC' ? 'ok' : 'warn'}`}>
              {preview.mode === 'ATOMIC' ? 'atomic — all or nothing' : 'online — staged, not atomic'}
            </span>
          </div>

          {preview.blockers.length > 0 && (
            <div className="banner banner-error">
              <strong>Pre-flight found blockers.</strong>
              <ul>{preview.blockers.map((b, i) => <li key={i}>{b}</li>)}</ul>
              <span className="small">
                These were checked against the real target table, not the branch sample.
              </span>
            </div>
          )}

          {preview.conflicts.length > 0 && (
            <>
              <h3>
                {preview.conflicts.filter((c) => !c.answered).length} of {preview.conflicts.length}{' '}
                conflict(s) need a decision
              </h3>
              {preview.conflicts.map((c) => (
                <ConflictCard
                  key={c.stableId + c.attribute}
                  conflict={c}
                  choice={resolutions[c.attribute ? `${c.stableId}:${c.attribute}` : c.stableId]}
                  onChoose={onChoose}
                />
              ))}
            </>
          )}

          {preview.autoResolved.length > 0 && (
            <details className="card">
              <summary>{preview.autoResolved.length} resolved automatically</summary>
              <ul className="changes">
                {preview.autoResolved.map((c, i) => <li key={i}>{c.question}</li>)}
              </ul>
            </details>
          )}

          {preview.warnings.length > 0 && (
            <div className="banner banner-warn">
              {preview.warnings.map((w, i) => <div key={i}>{w}</div>)}
            </div>
          )}

          <h3>Plan — {preview.steps.length} step(s)</h3>
          {preview.steps.length === 0
            ? <p className="muted">Nothing to merge; the target already has these changes.</p>
            : <PlanSteps steps={preview.steps} />}

          <div className="merge-footer">
            <span className="muted small">
              {preview.conflicts.filter((c) => !c.answered).length === 0
                ? 'All conflicts answered.'
                : `${preview.conflicts.filter((c) => !c.answered).length} conflict(s) outstanding.`}
            </span>
            <button className="primary" disabled={!preview.canApply || busy} onClick={onApplyMerge}>
              Merge into {preview.target}
            </button>
          </div>
        </>
      )}

      {tab === 'merge' && run && (
        <>
          <div className="merge-head">
            <h2>Migrating</h2>
            <span className={`badge badge-${run.status === 'SUCCEEDED' ? 'ok'
              : run.status === 'FAILED' ? 'danger' : 'warn'}`}>{run.status.toLowerCase()}</span>
          </div>
          {run.error_message && <pre className="error-box">{run.error_message}</pre>}
          <RunProgress steps={run.steps} />
          {run.status === 'SUCCEEDED' && (
            <div className="banner banner-ok">
              <strong>Merged.</strong> The change is live on {preview?.target ?? 'main'}.
            </div>
          )}
        </>
      )}

      {tab === 'history' && (
        <>
          {history.length === 0 && <p className="muted">No commits yet.</p>}
          {history.map((c) => (
            <section key={c.id} className="card">
              <header className="card-head">
                <h3>{c.message}</h3>
                <span className="muted small">
                  {c.author} · {new Date(c.createdAt).toLocaleString()}
                </span>
              </header>
              {c.operations.length > 0 && (
                <div className="sub">
                  {c.operations.map((o, i) => (
                    <div key={i}>
                      <span className="small muted">{o.type}</span>
                      <pre className="sql">{o.sql}</pre>
                    </div>
                  ))}
                </div>
              )}
            </section>
          ))}
        </>
      )}
    </main>
  )
}
