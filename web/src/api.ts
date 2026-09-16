const BASE = '/api'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!res.ok) {
    // The API returns a real explanation for user errors, so surface it rather than
    // replacing it with a status code. "Something went wrong" makes a tool unusable.
    const body = await res.json().catch(() => null)
    throw new Error(body?.error ?? `Request failed (${res.status})`)
  }
  return res.status === 204 ? (undefined as T) : res.json()
}

export type Project = { id: string; name: string; mainSchema: string }
export type Branch = {
  id: string; name: string; pgSchema: string; status: string
  headCommitId: string | null; isMain: boolean
}
export type Column = {
  id: string; name: string; type: string; nullable: boolean
  defaultExpr: string | null; identity: string | null; primaryKey: boolean
}
export type Index = {
  id: string; name: string; unique: boolean; method: string
  columns: string[]; constraintBacked: boolean
}
export type Constraint = { id: string; name: string; kind: string; columns: string[] }
export type Table = {
  id: string; name: string; columns: Column[]; indexes: Index[]
  constraints: Constraint[]; approxRows: number | null; totalSize: string | null
}
export type SchemaView = {
  tables: Table[]
  unmanaged: { kind: string; name: string; reason: string }[]
  contentHash: string
}
export type Change = {
  kind: string; table: string; object: string; description: string
  from: string | null; to: string | null; destructive: boolean
}
export type Diff = { sourceBranch: string; targetBranch: string; changes: Change[]; drifted: boolean }
export type Conflict = {
  type: string; severity: string; objectKind: string; stableId: string
  table: string; object: string; attribute: string | null
  base: string | null; ours: string | null; theirs: string | null
  question: string; autoResolved: boolean; answered: boolean
}
export type Step = {
  seq: number; group: string; kind: string; description: string; sql: string | null
  verdict: string; blocks: string; lockMode: string; rationale: string; pointOfNoReturn: boolean
}
export type MergePreview = {
  source: string; target: string; mode: string
  conflicts: Conflict[]; autoResolved: Conflict[]
  steps: Step[]; warnings: string[]; blockers: string[]; canApply: boolean
}
export type RunStep = {
  seq: number; group: string; kind: string; description: string; sql: string | null
  status: string; blocks: string; lockMode: string; pointOfNoReturn: boolean
  error: string | null; lockWaitMs: number | null
  rowsDone?: number; rowsEstimated?: number; batchSize?: number
}
export type Run = { id: string; status: string; mode: string; error_message: string | null; steps: RunStep[] }
export type Commit = {
  id: string; message: string; author: string; seq: number; createdAt: string
  operations: { type: string; targetKind: string; sql: string }[]
}
export type Operation = Record<string, unknown>

export const api = {
  projects: () => request<Project[]>('/projects'),
  branches: (projectId: string) => request<Branch[]>(`/projects/${projectId}/branches`),
  createBranch: (projectId: string, name: string, from = 'main') =>
    request<Branch>(`/projects/${projectId}/branches`, {
      method: 'POST', body: JSON.stringify({ from, name, author: 'you' }),
    }),
  deleteBranch: (id: string) => request<void>(`/branches/${id}`, { method: 'DELETE' }),
  schema: (branchId: string) => request<SchemaView>(`/branches/${branchId}/schema`),
  diff: (branchId: string) => request<Diff>(`/branches/${branchId}/diff`),
  history: (branchId: string) => request<Commit[]>(`/branches/${branchId}/history`),
  refresh: (branchId: string) =>
    request<Commit>(`/branches/${branchId}/refresh`, {
      method: 'POST', body: JSON.stringify({ author: 'you' }),
    }),
  applyOps: (branchId: string, operations: Operation[], message: string) =>
    request<Commit>(`/branches/${branchId}/operations`, {
      method: 'POST', body: JSON.stringify({ operations, message, author: 'you' }),
    }),
  mergePreview: (branchId: string, resolutions: Record<string, string> = {}) =>
    request<MergePreview>(`/branches/${branchId}/merge/preview`, {
      method: 'POST', body: JSON.stringify({ resolutions }),
    }),
  mergeApply: (branchId: string, resolutions: Record<string, string> = {}) =>
    request<{ runId: string; mode: string; steps: number }>(`/branches/${branchId}/merge/apply`, {
      method: 'POST', body: JSON.stringify({ resolutions, author: 'you' }),
    }),
  run: (runId: string) => request<Run>(`/runs/${runId}`),
}
