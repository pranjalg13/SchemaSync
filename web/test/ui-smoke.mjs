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

  // A rejected submit must keep the dialog open, show the server's reason, and keep the input.
  // The first version closed the dialog before the request finished and lost what was typed.
  await ordersCard.getByRole('button', { name: 'Add column' }).click()
  const add = page.locator('.modal')
  await add.waitFor({ timeout: 5000 })
  await add.locator('input').first().fill('customer_id')          // already exists on orders
  await add.getByRole('button', { name: 'Add column' }).click()
  await add.locator('.form-error').waitFor({ timeout: 10000 })
  if (await add.locator('input').first().inputValue() === 'customer_id') {
    pass('a rejected submit keeps the dialog open, explains why, and keeps the input')
  } else {
    fail('the dialog lost the user input after a rejected submit')
  }
  if (shotDir) await page.screenshot({ path: `${shotDir}/ui-form-error.png` })
  await page.keyboard.press('Escape')
  await add.waitFor({ state: 'detached', timeout: 5000 })

  // The destructive path must require typing the name; the button stays disabled until then.
  const notesRow = ordersCard.locator('.row', { hasText: 'notes' }).first()
  await notesRow.locator('button', { hasText: 'Drop' }).click()
  const confirm = page.locator('.modal')
  await confirm.waitFor({ timeout: 5000 })
  const dropBtn = confirm.getByRole('button', { name: /^Drop notes$/ })
  if (await dropBtn.isDisabled()) pass('dropping a column is disabled until the name is typed')
  else fail('destructive confirm was enabled before the name was typed')
  if (shotDir) await page.screenshot({ path: `${shotDir}/ui-confirm.png` })
  await confirm.locator('input').fill('notes')
  await dropBtn.click()
  await confirm.waitFor({ state: 'detached', timeout: 10000 })
  const afterDrop = await api(`/branches/${branch.id}/diff`)
  if (afterDrop.changes.some((c) => c.kind === 'COLUMN_DROPPED' && c.object === 'notes')) {
    pass('typing the name and confirming actually drops the column')
  } else {
    fail('confirmed drop did not reach the branch')
  }

  // Filter matches column names across tables.
  await page.keyboard.press('/')
  await page.keyboard.type('customer_id')
  await page.waitForTimeout(300)
  const shown = await page.locator('.schema-main .card').count()
  if (shown >= 1 && shown < 4) pass(`"/" focuses the filter; "customer_id" narrows to ${shown} table(s)`)
  else fail(`filter showed ${shown} tables`)
  await page.keyboard.press('Escape')

  // N opens the New branch dialog (the last prompt() in the app, replaced).
  await page.locator('body').click({ position: { x: 5, y: 300 } })
  await page.keyboard.press('n')
  const nb = page.locator('.modal', { hasText: 'New branch' })
  await nb.waitFor({ timeout: 5000 })
  pass('"N" opens the New branch dialog')
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
