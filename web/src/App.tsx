import { useCallback, useEffect, useRef, useState } from 'react'
import {
  api, type Branch, type Column, type Commit, type Diff, type MergePreview,
  type Operation, type Project, type Run, type SchemaView, type Table,
} from './api'
import { BranchSwitcher } from './BranchSwitcher'
import { ChangeList, ConflictCard, PlanSteps, RunProgress } from './components'
import { AddColumnForm, AddIndexForm, ColumnEditor, CreateTableForm, NewBranchForm } from './forms'
import { SchemaTab } from './SchemaTab'
import { ConfirmDestructive, CopyButton, Skeleton, Toast, TopProgress, timeAgo, useHotkey } from './ui'

type Tab = 'schema' | 'changes' | 'merge' | 'history'

const initialHash = new URLSearchParams(typeof location === 'undefined' ? '' : location.hash.slice(1))

type Dialog =
  | { kind: 'editColumn'; table: Table; column: Column }
  | { kind: 'addColumn'; table: Table }
  | { kind: 'addIndex'; table: Table }
  | { kind: 'createTable' }
  | { kind: 'newBranch' }
  | { kind: 'confirm'; title: string; what: string; consequence: string; run: () => Promise<void> }
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
  const [history, setHistory] = useState<Commit[] | null>(null)
  const [preview, setPreview] = useState<MergePreview | null>(null)
  const [resolutions, setResolutions] = useState<Record<string, string>>({})
  const [run, setRun] = useState<Run | null>(null)
  const [starting, setStarting] = useState(false)

  const [dialog, setDialog] = useState<Dialog>(null)
  const [toast, setToast] = useState<{ message: string; tone: 'ok' | 'error' } | null>(null)
  const [busy, setBusy] = useState(0)
  const pollRef = useRef<number | null>(null)

  /** Runs a background action, driving the top progress bar and surfacing failures as a toast. */
  const guard = useCallback(async (fn: () => Promise<void>) => {
    setBusy((n) => n + 1)
    try { await fn() } catch (e) { setToast({ message: (e as Error).message, tone: 'error' }) }
    finally { setBusy((n) => n - 1) }
  }, [])

  useEffect(() => {
    guard(async () => {
      const projects = await api.projects()
      if (projects.length === 0) { setHydrated(true); return }
      setProject(projects[0])
      const bs = await api.branches(projects[0].id)
      setBranches(bs)
      const wanted = initialHash.get('branch')
      setCurrent(bs.find((b) => b.id === wanted)
        ?? bs.find((b) => !b.isMain && b.status === 'ACTIVE') ?? bs[0] ?? null)
      setHydrated(true)
    })
  }, [guard])

  useEffect(() => {
    if (!current) return
    setSchema(null); setDiff(null); setHistory(null); setPreview(null); setRun(null); setResolutions({})
    guard(async () => {
      const [s, d] = await Promise.all([api.schema(current.id), api.diff(current.id)])
      setSchema(s); setDiff(d)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.id])

  useEffect(() => {
    // Write the hash only once the initial selection has settled, or this clobbers the branch
    // the incoming URL asked for.
    if (!hydrated) return
    const params = new URLSearchParams({ tab })
    if (current) params.set('branch', current.id)
    window.history.replaceState(null, '', `#${params.toString()}`)
  }, [hydrated, tab, current?.id])

  const refreshBranch = useCallback(async () => {
    if (!current || !project) return
    const [s, d, bs] = await Promise.all([
      api.schema(current.id), api.diff(current.id), api.branches(project.id)])
    setSchema(s); setDiff(d); setBranches(bs)
    setCurrent((c) => bs.find((b) => b.id === c?.id) ?? c)
  }, [current, project])

  /** Applies operations; THROWS on failure so the dialog that asked can keep the user's input. */
  const submitOps = async (ops: Operation[], message: string) => {
    if (!current) return
    await api.applyOps(current.id, ops, message)
    await refreshBranch()
    setPreview(null)
    setToast({ message, tone: 'ok' })
  }

  const createBranch = async (name: string, from: string) => {
    if (!project) return
    const created = await api.createBranch(project.id, name, from)
    setBranches(await api.branches(project.id))
    setCurrent(created)
    setTab('schema')
    setToast({ message: `Branched ${name} from ${from}`, tone: 'ok' })
  }

  useHotkey('n', () => setDialog({ kind: 'newBranch' }), { enabled: dialog === null && !!project })

  const onDeleteBranch = () => {
    if (!current || current.isMain) return
    const doomed = current
    setDialog({
      kind: 'confirm',
      title: `Delete branch ${doomed.name}?`,
      what: doomed.name,
      consequence: 'Its Postgres schema is dropped. If it was merged, its commits stay as shared '
        + 'history, because main references them.',
      run: async () => {
        if (!project) return
        await api.deleteBranch(doomed.id)
        const bs = await api.branches(project.id)
        setBranches(bs)
        setCurrent(bs.find((b) => !b.isMain && b.status === 'ACTIVE') ?? bs[0] ?? null)
        setToast({ message: `Deleted ${doomed.name}`, tone: 'ok' })
      },
    })
  }

  const onRefresh = () => guard(async () => {
    if (!current) return
    await api.refresh(current.id)
    await refreshBranch()
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

  const onApplyMerge = async () => {
    if (!current || starting) return
    setStarting(true)
    try {
      const started = await api.mergeApply(current.id, resolutions)
      setRun(await api.run(started.runId))
      // Polling, not streaming: a plan is tens of rows, so re-reading all of it is cheap, and a
      // dropped connection repairs itself on the next tick with no replay logic.
      if (pollRef.current) window.clearInterval(pollRef.current)
      pollRef.current = window.setInterval(async () => {
        try {
          const r = await api.run(started.runId)
          setRun(r)
          if (r.status === 'SUCCEEDED' || r.status === 'FAILED') {
            window.clearInterval(pollRef.current!)
            pollRef.current = null
            await refreshBranch()
            setToast({ message: r.status === 'SUCCEEDED' ? 'Merged into main' : 'The migration stopped',
                       tone: r.status === 'SUCCEEDED' ? 'ok' : 'error' })
          }
        } catch { /* transient; keep polling */ }
      }, 700)
    } catch (e) {
      setToast({ message: (e as Error).message, tone: 'error' })
    } finally {
      setStarting(false)
    }
  }

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
        <div className="empty">
          <h3>No project yet</h3>
          <p>Set <code>SCHEMASYNC_DEMO_SEED_ORDERS</code> or run <code>./scripts/seed.sh</code>,
             then restart the API.</p>
        </div>
        {toast && <Toast {...toast} onDone={() => setToast(null)} />}
      </main>
    )
  }

  const changeCount = diff?.changes.length ?? 0
  const openConflicts = preview?.conflicts.filter((c) => !c.answered).length ?? 0
  const readOnly = !!current?.isMain

  return (
    <>
      <TopProgress active={busy > 0 || (run != null && run.status === 'RUNNING')} />
      <header className="appbar">
        <div className="appbar-inner">
          <div className="brand">
            <span className="logo" aria-hidden="true">◆</span>
            <span className="brand-name">SchemaSync</span>
            {project && <span className="brand-sub">{project.name} · <code>{project.mainSchema}</code></span>}
          </div>
          <div className="appbar-actions">
            <BranchSwitcher branches={branches} current={current}
                            onSelect={(b) => { setCurrent(b); if (b.isMain && tab === 'merge') setTab('schema') }}
                            onNew={() => setDialog({ kind: 'newBranch' })} />
            {current && !current.isMain && (
              <button className="ghost-danger" onClick={onDeleteBranch}>Delete</button>
            )}
          </div>
        </div>
        <nav className="tabs appbar-inner">
          {(['schema', 'changes', 'merge', 'history'] as Tab[])
            .filter((t) => t !== 'merge' || !readOnly)
            .map((t) => (
              <button key={t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>
                {t[0].toUpperCase() + t.slice(1)}
                {t === 'changes' && changeCount > 0 && <span className="tab-count">{changeCount}</span>}
                {t === 'merge' && openConflicts > 0 && <span className="tab-count warn">{openConflicts}</span>}
              </button>
            ))}
        </nav>
      </header>

      <main className="shell">
        {diff?.drifted && (
          <div className="banner banner-warn">
            <span><strong>This branch has drifted.</strong> Its schema changed outside SchemaSync, so
              rename tracking is unreliable and merging is blocked.</span>
            <button onClick={onRefresh}>Re-import</button>
          </div>
        )}

        {readOnly && (
          <div className="banner banner-info">
            <span><strong>You are viewing main, read-only.</strong> Changes reach it only through a
              merge, which is the path with safety analysis and online migration.</span>
            <button onClick={() => setDialog({ kind: 'newBranch' })}>New branch</button>
          </div>
        )}

        {tab === 'schema' && (schema ? (
          <SchemaTab
            schema={schema}
            readOnly={readOnly}
            onEditColumn={(table, column) => setDialog({ kind: 'editColumn', table, column })}
            onAddColumn={(table) => setDialog({ kind: 'addColumn', table })}
            onAddIndex={(table) => setDialog({ kind: 'addIndex', table })}
            onCreateTable={() => setDialog({ kind: 'createTable' })}
            onDropColumn={(table, column) => setDialog({
              kind: 'confirm', title: `Drop ${table.name}.${column.name}?`, what: column.name,
              consequence: 'It goes from this branch now. When you merge, its data is permanently '
                + 'deleted from the target — that cannot be undone.',
              run: () => submitOps([{ op: 'DROP_COLUMN', tableId: table.id, columnId: column.id }],
                `Drop ${table.name}.${column.name}`),
            })}
            onDropIndex={(table, indexId, name) => guard(() => submitOps(
              [{ op: 'DROP_INDEX', tableId: table.id, indexId }], `Drop index ${name}`))}
            onDropTable={(table) => setDialog({
              kind: 'confirm', title: `Drop table ${table.name}?`, what: table.name,
              consequence: `On merge this permanently deletes the table and all `
                + `${(table.approxRows ?? 0).toLocaleString()} of its rows.`,
              run: () => submitOps([{ op: 'DROP_TABLE', tableId: table.id }], `Drop table ${table.name}`),
            })}
          />
        ) : <><Skeleton rows={4} /><Skeleton rows={6} /><Skeleton rows={3} /></>)}

        {tab === 'changes' && (diff ? (
          <>
            <p className="lede">Compared against the point this branch forked from. This is
              <em> what is different</em>; History is <em>how it got there</em>.</p>
            <ChangeList changes={diff.changes} />
          </>
        ) : <Skeleton rows={4} />)}

        {tab === 'merge' && !run && (preview ? (
          <>
            <div className="merge-head">
              <h2>{preview.source} <span className="arrow">→</span> {preview.target}</h2>
              <span className={`badge badge-${preview.mode === 'ATOMIC' ? 'ok' : 'warn'}`}>
                {preview.mode === 'ATOMIC' ? 'atomic · all or nothing' : 'online · staged, not atomic'}
              </span>
            </div>

            {preview.blockers.length > 0 && (
              <div className="banner banner-error">
                <span style={{ width: '100%' }}><strong>Pre-flight found blockers.</strong> Checked
                  against the real target table, not this branch's sample.</span>
                <ul>{preview.blockers.map((b, i) => <li key={i}>{b}</li>)}</ul>
              </div>
            )}

            {preview.conflicts.length > 0 && (
              <>
                <h3 className="section-title">
                  {openConflicts === 0 ? `${preview.conflicts.length} conflict(s), all answered`
                    : `${openConflicts} of ${preview.conflicts.length} conflict(s) need a decision`}
                </h3>
                {preview.conflicts.map((c) => (
                  <ConflictCard key={c.stableId + c.attribute} conflict={c} onChoose={onChoose}
                    choice={resolutions[c.attribute ? `${c.stableId}:${c.attribute}` : c.stableId]} />
                ))}
              </>
            )}

            {preview.autoResolved.length > 0 && (
              <details className="card details">
                <summary>{preview.autoResolved.length} resolved automatically</summary>
                <ul className="changes">{preview.autoResolved.map((c, i) => <li key={i}>{c.question}</li>)}</ul>
              </details>
            )}

            {preview.warnings.length > 0 && (
              <div className="banner banner-warn"><span>{preview.warnings.map((w, i) => <div key={i}>{w}</div>)}</span></div>
            )}

            {preview.steps.length === 0 ? (
              <div className="empty"><h3>Nothing to merge</h3><p>{preview.target} already has everything on this branch.</p></div>
            ) : (
              <>
                <h3 className="section-title">Plan · {preview.steps.length} step(s)</h3>
                <PlanSteps steps={preview.steps} />
                <div className="merge-footer">
                  <span className="muted small">
                    {openConflicts === 0 && preview.blockers.length === 0 ? 'Ready to merge.'
                      : openConflicts > 0 ? `${openConflicts} conflict(s) outstanding.`
                      : 'Resolve the blockers above first.'}
                  </span>
                  <button className="primary" disabled={!preview.canApply || starting} onClick={onApplyMerge}>
                    {starting ? 'Starting…' : `Merge into ${preview.target}`}
                  </button>
                </div>
              </>
            )}
          </>
        ) : <Skeleton rows={5} />)}

        {tab === 'merge' && run && (
          <>
            <div className="merge-head">
              <h2>Migrating {preview?.target ?? 'main'}</h2>
              <span className={`badge badge-${run.status === 'SUCCEEDED' ? 'ok'
                : run.status === 'FAILED' ? 'danger' : 'warn'}`}>{run.status.toLowerCase()}</span>
            </div>
            {run.error_message && <pre className="error-box">{run.error_message}</pre>}
            <RunProgress steps={run.steps} status={run.status} />
            {run.status === 'SUCCEEDED' && (
              <div className="banner banner-ok">
                <span><strong>Merged.</strong> The change is live on {preview?.target ?? 'main'}.</span>
              </div>
            )}
          </>
        )}

        {tab === 'history' && (history == null ? <Skeleton rows={3} /> : history.length === 0 ? (
          <div className="empty"><h3>No commits yet</h3></div>
        ) : (
          <ol className="timeline">
            {history.map((c) => (
              <li key={c.id} className="timeline-item">
                <div className="timeline-dot" />
                <section className="card">
                  <header className="card-head">
                    <h3>{c.message}</h3>
                    <span className="muted small push" title={new Date(c.createdAt).toLocaleString()}>
                      {c.author} · {timeAgo(c.createdAt)}
                    </span>
                  </header>
                  {c.operations.length > 0 && (
                    <div className="card-sub">
                      {c.operations.map((o, i) => (
                        <div key={i}>
                          <div className="op-head">
                            <span className="muted small">{o.type.replaceAll('_', ' ').toLowerCase()}</span>
                            <CopyButton text={o.sql} label="Copy SQL" />
                          </div>
                          <pre className="sql">{o.sql}</pre>
                        </div>
                      ))}
                    </div>
                  )}
                </section>
              </li>
            ))}
          </ol>
        ))}
      </main>

      {dialog?.kind === 'editColumn' && (
        <ColumnEditor table={dialog.table} column={dialog.column} onSubmit={submitOps} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'addColumn' && (
        <AddColumnForm table={dialog.table} onSubmit={submitOps} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'addIndex' && (
        <AddIndexForm table={dialog.table} onSubmit={submitOps} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'createTable' && (
        <CreateTableForm onSubmit={submitOps} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'newBranch' && (
        <NewBranchForm branches={branches} onCreate={createBranch} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'confirm' && (
        <ConfirmDestructive title={dialog.title} what={dialog.what} consequence={dialog.consequence}
                            onConfirm={dialog.run} onClose={() => setDialog(null)} />
      )}
      {toast && <Toast {...toast} onDone={() => setToast(null)} />}
    </>
  )
}
