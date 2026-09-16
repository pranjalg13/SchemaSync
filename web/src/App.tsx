import { useEffect, useState } from 'react'

type Health = { status: string; mainSchema: string; branches: number }

export function App() {
  const [health, setHealth] = useState<Health | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetch('/api/health')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then(setHealth)
      .catch((e: Error) => setError(e.message))
  }, [])

  return (
    <main className="shell">
      <header>
        <h1>SchemaSync</h1>
        <p className="tagline">Version control for your Postgres schema.</p>
      </header>
      {error && <p className="error">Cannot reach the API: {error}</p>}
      {health && (
        <p className="ok">
          Connected. Managing schema <code>{health.mainSchema}</code> &middot;{' '}
          {health.branches} branch{health.branches === 1 ? '' : 'es'}.
        </p>
      )}
      {!health && !error && <p className="muted">Connecting&hellip;</p>}
    </main>
  )
}
