/**
 * UI smoke test: drives the real app in a real browser against the real API.
 *
 * Narrow on purpose. It does not try to be a full UI suite -- it checks that the one interaction
 * the redesign introduced actually works end to end: opening the column editor, changing two
 * attributes at once, and having that reach Postgres as a SINGLE commit containing both
 * operations. That combination is the whole reason the editor replaced a chain of prompt() calls,
 * so it is the thing worth guarding.
 *
 *   node test/ui-smoke.mjs [baseUrl]
 */
import { chromium } from 'playwright'

const BASE = process.argv[2] ?? 'http://127.0.0.1:5173'
const API = `${BASE}/api`
const shotDir = process.env.SHOT_DIR

let failures = 0
const pass = (m) => console.log(`  \x1b[32mPASS\x1b[0m  ${m}`)
const fail = (m) => { failures++; console.log(`  \x1b[31mFAIL\x1b[0m  ${m}`) }

const api = async (path, init) => {
  const res = await fetch(API + path, {
    headers: { 'Content-Type': 'application/json' }, ...init,
  })
  if (!res.ok) throw new Error(`${path} -> ${res.status} ${await res.text()}`)
  return res.status === 204 ? null : res.json()
}

const branchName = `ui_smoke_${Date.now()}`
const [project] = await api('/projects')
const branch = await api(`/projects/${project.id}/branches`, {
  method: 'POST',
  body: JSON.stringify({ from: 'main', name: branchName, author: 'ui-smoke' }),
})

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1180, height: 900 } })
page.on('pageerror', (e) => fail(`uncaught page error: ${e.message}`))

try {
  await page.goto(`${BASE}/#tab=schema&branch=${branch.id}`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.card', { timeout: 15000 })
  pass('schema loads and renders table cards')

  // Find the orders.status row and open its editor.
  const ordersCard = page.locator('.card', { has: page.getByRole('heading', { name: /^orders/ }) }).first()
  const statusRow = ordersCard.locator('.row', { hasText: 'status' }).first()
  await statusRow.locator('button', { hasText: 'Edit' }).click()

  const modal = page.locator('.modal')
  await modal.waitFor({ timeout: 5000 })
  pass('the column editor opens')

  if (shotDir) await page.screenshot({ path: `${shotDir}/ui-editor.png` })

  // Change the name AND the type in one dialog. Two prompt() calls became one form, and the
  // point is that they arrive as one commit.
  await modal.locator('input').first().fill('order_status')
  await modal.locator('input').nth(1).fill('varchar(64)')
  await modal.getByRole('button', { name: 'Save' }).click()
  await modal.waitFor({ state: 'detached', timeout: 10000 })
  pass('the editor submits and closes')

  await page.waitForTimeout(1200)

  const history = await api(`/branches/${branch.id}/history`)
  const latest = history[0]
  const kinds = latest.operations.map((o) => o.type).sort()
  if (kinds.length === 2 && kinds.includes('RENAME_COLUMN') && kinds.includes('CHANGE_COLUMN_TYPE')) {
    pass('rename + retype arrived as ONE commit with both operations')
  } else {
    fail(`expected one commit with 2 operations, got ${latest.operations.length}: ${kinds}`)
  }

  const diff = await api(`/branches/${branch.id}/diff`)
  const renames = diff.changes.filter((c) => c.kind === 'COLUMN_RENAMED')
  const drops = diff.changes.filter((c) => c.kind === 'COLUMN_DROPPED')
  if (renames.length === 1 && drops.length === 0) {
    pass('the diff still reports a rename, not a drop plus an add')
  } else {
    fail(`expected 1 rename and 0 drops, got ${renames.length} and ${drops.length}`)
  }

  // The destructive path must require typing the name, not just a click.
  const notesRow = ordersCard.locator('.row', { hasText: 'notes' }).first()
  await notesRow.locator('button', { hasText: 'Drop' }).click()
  const confirm = page.locator('.modal')
  await confirm.waitFor({ timeout: 5000 })
  const dropBtn = confirm.getByRole('button', { name: /^Drop notes$/ })
  await dropBtn.click()
  await page.waitForTimeout(400)
  if (await confirm.isVisible()) {
    pass('dropping a column refuses to proceed until the name is typed')
  } else {
    fail('destructive confirm accepted a bare click')
  }
  if (shotDir) await page.screenshot({ path: `${shotDir}/ui-confirm.png` })
  await page.keyboard.press('Escape')

  await page.locator('nav.tabs button', { hasText: 'Merge' }).click()
  await page.waitForSelector('.plan, .empty', { timeout: 20000 })
  pass('the merge tab builds a plan')
  if (shotDir) await page.screenshot({ path: `${shotDir}/ui-plan.png`, fullPage: true })
} finally {
  await browser.close()
  await api(`/branches/${branch.id}`, { method: 'DELETE' }).catch(() => {})
}

console.log(failures === 0
  ? '\n\x1b[32mUI smoke test passed.\x1b[0m'
  : `\n\x1b[31m${failures} check(s) failed.\x1b[0m`)
process.exit(failures === 0 ? 0 : 1)
