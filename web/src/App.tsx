import { useCallback, useEffect, useRef, useState } from 'react'
import {
  api, type Branch, type Column, type Commit, type Diff, type MergePreview,
  type Operation, type Project, type Run, type SchemaView, type Table,
} from './api'
import { ChangeList, ConflictCard, PlanSteps, RunProgress, TableCard } from './components'
import { AddColumnForm, AddIndexForm, ColumnEditor, CreateTableForm } from './forms'
import { ConfirmDestructive, Toast } from './ui'

type Tab = 'schema' | 'changes' | 'merge' | 'history'

const initialHash = new URLSearchParams(
  typeof location === 'undefined' ? '' : location.hash.slice(1))

type Dialog =
  | { kind: 'editColumn'; table: Table; column: Column }
  | { kind: 'addColumn'; table: Table }
  | { kind: 'addIndex'; table: Table }
  | { kind: 'createTable' }
  | { kind: 'confirm'; title: string; what: string; consequence: string; run: () => void }
  | null

export function App() {
  const [project, setProject] = useState<Project | null>(null)
  const [branches, setBranches] = useState<Branch[]>([])
  const [current, setCurrent] = useState<Branch | null>(null)
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

  const [dialog, setDialog] = useState<Dialog>(null)
  const [toast, setToast] = useState<{ message: string; tone: 'ok' | 'error' } | null>(null)
  const [busy, setBusy] = useState(false)
  const pollRef = useRef<number | null>(null)

  const guard = useCallback(async (fn: () => Promise<void>) => {
    setBusy(true)
    try { await fn() } catch (e) { setToast({ message: (e as Error).message, tone: 'error' }) }
    finally { setBusy(false) }
  }, [])

  useEffect(() => {
    guard(async () => {
      const projects = await api.projects()
      if (projects.length === 0) { setHydrated(true); return }
      setProject(projects[0])
      const bs = await api.branches(projects[0].id)
      setBranches(bs)
      const wanted = initialHash.get('branch')
      const requested = wanted ? bs.find((b) => b.id === wanted) : undefined
      setCurrent(requested ?? bs.find((b) => !b.isMain && b.status === 'ACTIVE') ?? bs[0] ?? null)
      setHydrated(true)
    })
  }, [guard])

  const loadBranch = useCallback((branch: Branch) => guard(async () => {
    setPreview(null); setRun(null); setResolutions({})
    setSchema(await api.schema(branch.id))
    setDiff(await api.diff(branch.id))
  }), [guard])

  useEffect(() => {
    if (current) loadBranch(current)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.id])

  useEffect(() => {
    // Only write the hash once the initial selection has settled, or this clobbers the branch
    // the incoming URL asked for.
    if (!hydrated) return
    const params = new URLSearchParams()
    params.set('tab', tab)
    if (current) params.set('branch', current.id)
    window.history.replaceState(null, '', `#${params.toString()}`)
  }, [hydrated, tab, current?.id])

  const reload = useCallback(() => guard(async () => {
    if (!current || !project) return
    setSchema(await api.schema(current.id))
    setDiff(await api.diff(current.id))
    setBranches(await api.branches(project.id))
  }), [current, project, guard])

  const applyOps = (ops: Operation[], message: string) => guard(async () => {
    if (!current) return
    await api.applyOps(current.id, ops, message)
    setSchema(await api.schema(current.id))
    setDiff(await api.diff(current.id))
    setPreview(null)
    setToast({ message, tone: 'ok' })
  })

  // ----- branch actions -------------------------------------------------

  const onNewBranch = () => guard(async () => {
    if (!project) return
    const name = prompt('Name for the new branch:', 'my_change')
    if (!name) return
    const created = await api.createBranch(project.id, name)
    setBranches(await api.branches(project.id))
    setCurrent(created)
    setToast({ message: `Branched ${name} from main`, tone: 'ok' })
  })

  const onDeleteBranch = () => {
    if (!current || current.isMain) return
    setDialog({
      kind: 'confirm',
      title: `Delete branch ${current.name}?`,
      what: current.name,
      consequence: 'Its Postgres schema is dropped. If it was merged, its commits are kept as '
        + 'shared history — main references them.',
      run: () => guard(async () => {
        if (!project) return
        await api.deleteBranch(current.id)
        const bs = await api.branches(project.id)
        setBranches(bs)
        setCurrent(bs.find((b) => !b.isMain && b.status === 'ACTIVE') ?? bs[0] ?? null)
        setToast({ message: `Deleted ${current.name}`, tone: 'ok' })
      }),
    })
  }

  const onRefresh = () => guard(async () => {
    if (!current) return
    await api.refresh(current.id)
    await reload()
    setToast({ message: 'Re-imported the live schema', tone: 'ok' })
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
    // Polling rather than streaming: a plan is tens of rows, so re-reading the whole state is
    // cheap and a dropped connection repairs itself on the next tick with no replay logic.
    if (pollRef.current) window.clearInterval(pollRef.current)
    pollRef.current = window.setInterval(async () => {
      try {
        const r = await api.run(started.runId)
        setRun(r)
        if (r.status === 'SUCCEEDED' || r.status === 'FAILED') {
          window.clearInterval(pollRef.current!)
          pollRef.current = null
          await reload()
          setToast({
            message: r.status === 'SUCCEEDED' ? 'Merged into main' : 'Migration failed',
            tone: r.status === 'SUCCEEDED' ? 'ok' : 'error',
          })
        }
      } catch { /* transient; keep polling */ }
    }, 700)
  })

  useEffect(() => () => { if (pollRef.current) window.clearInterval(pollRef.current) }, [])

  useEffect(() => {
    if (tab === 'merge' && current && !current.isMain && !preview && !run) loadPreview()
    if (tab === 'history' && current) guard(async () => setHistory(await api.history(current.id)))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, current?.id])

  // ----- render ---------------------------------------------------------

  if (hydrated && !project) {
    return (
      <main className="shell">
        <h1>SchemaSync</h1>
        <div className="empty">
          <h3>No project yet</h3>
          <p>Run <code>./scripts/seed.sh</code> to create the demo schema, then restart the API.</p>
        </div>
        {toast && <Toast {...toast} onDone={() => setToast(null)} />}
      </main>
    )
  }
  if (!project) return <main className="shell"><p className="muted">Loading…</p></main>

  const changeCount = diff?.changes.length ?? 0
  const openConflicts = preview?.conflicts.filter((c) => !c.answered).length ?? 0
  const readOnly = !!current?.isMain

  return (
    <main className="shell">
      <header className="top">
        <div>
          <h1>SchemaSync</h1>
          <p className="tagline">Version control for <code>{project.mainSchema}</code></p>
        </div>
        <div className="branch-bar">
          <select
            value={current?.id ?? ''}
            onChange={(e) => setCurrent(branches.find((b) => b.id === e.target.value) ?? null)}
          >
            {branches.map((b) => (
              <option key={b.id} value={b.id}>
                {b.name}
                {b.isMain ? ' · main' : ''}
                {b.status === 'DRIFTED' ? ' · drifted' : ''}
                {b.status === 'MERGED' ? ' · merged' : ''}
              </option>
            ))}
          </select>
          <button onClick={onNewBranch}>New branch</button>
          {current && !current.isMain && (
            <button className="ghost-danger" onClick={onDeleteBranch}>Delete</button>
          )}
        </div>
      </header>

      {diff?.drifted && (
        <div className="banner banner-warn">
          <span>
            <strong>This branch has drifted.</strong> Its schema changed outside SchemaSync, so
            rename tracking is no longer reliable and merging is blocked.
          </span>
          <button onClick={onRefresh}>Re-import</button>
        </div>
      )}

      {readOnly && (
        <div className="banner banner-info">
          <span>
            <strong>This is main.</strong> It is read-only here — that is the one path that gets
            safety analysis and an online migration. Branch it and merge the change back.
          </span>
        </div>
      )}

      <nav className="tabs">
        <button className={tab === 'schema' ? 'on' : ''} onClick={() => setTab('schema')}>Schema</button>
        <button className={tab === 'changes' ? 'on' : ''} onClick={() => setTab('changes')}>
          Changes{changeCount > 0 && <span className="tab-count">{changeCount}</span>}
        </button>
        {!readOnly && (
          <button className={tab === 'merge' ? 'on' : ''} onClick={() => setTab('merge')}>
            Merge{openConflicts > 0 && <span className="tab-count">{openConflicts}</span>}
          </button>
        )}
        <button className={tab === 'history' ? 'on' : ''} onClick={() => setTab('history')}>History</button>
        {busy && <span className="spinner">working…</span>}
      </nav>

      {tab === 'schema' && schema && (
        <>
          {schema.unmanaged.length > 0 && (
            <div className="banner banner-info">
              <span>
                <strong>Partially managed.</strong> {schema.unmanaged.length} object(s) SchemaSync
                does not model are present and will be left alone:{' '}
                {schema.unmanaged.map((u) => `${u.name} (${u.kind})`).join(', ')}
              </span>
            </div>
          )}
          {!readOnly && (
            <div className="toolbar">
              <button onClick={() => setDialog({ kind: 'createTable' })}>New table</button>
            </div>
          )}
          {schema.tables.map((t) => (
            <TableCard
              key={t.id}
              table={t}
              readOnly={readOnly}
              onEditColumn={(table, column) => setDialog({ kind: 'editColumn', table, column })}
              onAddColumn={(table) => setDialog({ kind: 'addColumn', table })}
              onAddIndex={(table) => setDialog({ kind: 'addIndex', table })}
              onDropColumn={(table, column) => setDialog({
                kind: 'confirm',
                title: `Drop ${table.name}.${column.name}?`,
                what: column.name,
                consequence: 'The column goes from this branch now. When you merge, its data is '
                  + 'permanently deleted from the target — that cannot be undone.',
                run: () => applyOps(
                  [{ op: 'DROP_COLUMN', tableId: table.id, columnId: column.id }],
                  `Drop ${table.name}.${column.name}`),
              })}
              onDropIndex={(table, indexId, name) => applyOps(
                [{ op: 'DROP_INDEX', tableId: table.id, indexId }], `Drop index ${name}`)}
              onDropTable={(table) => setDialog({
                kind: 'confirm',
                title: `Drop table ${table.name}?`,
                what: table.name,
                consequence: `On merge this permanently deletes the table and all `
                  + `${(table.approxRows ?? 0).toLocaleString()} of its rows.`,
                run: () => applyOps(
                  [{ op: 'DROP_TABLE', tableId: table.id }], `Drop table ${table.name}`),
              })}
            />
          ))}
        </>
      )}

      {tab === 'changes' && diff && (
        <>
          <p className="muted small" style={{ marginBottom: 12 }}>
            Compared against the point this branch forked from. This is <em>what is different</em>;
            History is <em>how it got there</em>.
          </p>
          <ChangeList changes={diff.changes} />
        </>
      )}

      {tab === 'merge' && !run && preview && (
        <>
          <div className="merge-head">
            <h2>{preview.source} <span className="arrow">→</span> {preview.target}</h2>
            <span className={`badge badge-${preview.mode === 'ATOMIC' ? 'ok' : 'warn'}`}>
              {preview.mode === 'ATOMIC' ? 'atomic — all or nothing' : 'online — staged, not atomic'}
            </span>
          </div>

          {preview.blockers.length > 0 && (
            <div className="banner banner-error">
              <span style={{ width: '100%' }}>
                <strong>Pre-flight found blockers.</strong> These were checked against the real
                target table, not this branch's sample.
              </span>
              <ul>{preview.blockers.map((b, i) => <li key={i}>{b}</li>)}</ul>
            </div>
          )}

          {preview.conflicts.length > 0 && (
            <>
              <h3 style={{ margin: '16px 0 10px' }}>
                {openConflicts === 0
                  ? `${preview.conflicts.length} conflict(s), all answered`
                  : `${openConflicts} of ${preview.conflicts.length} conflict(s) need a decision`}
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
            <details className="card" style={{ padding: '11px 14px' }}>
              <summary className="muted small">
                {preview.autoResolved.length} resolved automatically
              </summary>
              <ul className="changes" style={{ marginTop: 8 }}>
                {preview.autoResolved.map((c, i) => <li key={i}>{c.question}</li>)}
              </ul>
            </details>
          )}

          {preview.warnings.length > 0 && (
            <div className="banner banner-warn">
              <span>{preview.warnings.map((w, i) => <div key={i}>{w}</div>)}</span>
            </div>
          )}

          {preview.steps.length === 0 ? (
            <div className="empty">
              <h3>Nothing to merge</h3>
              <p>{preview.target} already has everything on this branch.</p>
            </div>
          ) : (
            <>
              <h3 style={{ margin: '16px 0 10px' }}>Plan · {preview.steps.length} step(s)</h3>
              <PlanSteps steps={preview.steps} />
            </>
          )}

          {preview.steps.length > 0 && (
            <div className="merge-footer">
              <span className="muted small">
                {openConflicts === 0 && preview.blockers.length === 0
                  ? 'Ready to merge.'
                  : openConflicts > 0
                    ? `${openConflicts} conflict(s) outstanding.`
                    : 'Resolve the blockers above first.'}
              </span>
              <button className="primary" disabled={!preview.canApply || busy} onClick={onApplyMerge}>
                Merge into {preview.target}
              </button>
            </div>
          )}
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
            <div className="banner banner-ok" style={{ marginTop: 12 }}>
              <span><strong>Merged.</strong> The change is live on {preview?.target ?? 'main'}.</span>
            </div>
          )}
        </>
      )}

      {tab === 'history' && (
        history.length === 0 ? (
          <div className="empty"><h3>No commits yet</h3></div>
        ) : history.map((c) => (
          <section className="card" key={c.id}>
            <header className="card-head">
              <h3>{c.message}</h3>
              <span className="muted small" style={{ marginLeft: 'auto' }}>
                {c.author} · {new Date(c.createdAt).toLocaleString()}
              </span>
            </header>
            {c.operations.length > 0 && (
              <div className="card-sub">
                {c.operations.map((o, i) => (
                  <div key={i}>
                    <span className="muted small">{o.type}</span>
                    <pre className="sql">{o.sql}</pre>
                  </div>
                ))}
              </div>
            )}
          </section>
        ))
      )}

      {dialog?.kind === 'editColumn' && (
        <ColumnEditor table={dialog.table} column={dialog.column}
                      onSubmit={applyOps} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'addColumn' && (
        <AddColumnForm table={dialog.table} onSubmit={applyOps} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'addIndex' && (
        <AddIndexForm table={dialog.table} onSubmit={applyOps} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'createTable' && (
        <CreateTableForm onSubmit={applyOps} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'confirm' && (
        <ConfirmDestructive
          title={dialog.title} what={dialog.what} consequence={dialog.consequence}
          onConfirm={dialog.run} onClose={() => setDialog(null)}
        />
      )}

      {toast && <Toast {...toast} onDone={() => setToast(null)} />}
    </main>
  )
}
