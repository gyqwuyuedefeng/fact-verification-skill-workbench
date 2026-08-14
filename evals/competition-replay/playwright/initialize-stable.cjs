const fs = require('node:fs')
const path = require('node:path')

const {
  assert,
  collectPageDiagnostics,
  ensureEvidenceRoot,
  gotoRoute,
  openBrowser,
  saveScreenshot,
  assertNoDiagnostics,
} = require('./helpers.cjs')

const statePath = path.join(ensureEvidenceRoot(), 'full-demo-state.json')

async function main() {
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'))
  const candidateId = (state.bootstrapCandidate ?? state.initialCandidate)?.id
  const evaluationId = state.formalInitialEvaluation?.id || state.formalInitialEvaluationId
  assert(candidateId, '缺少首个 Candidate ID')
  assert(evaluationId, '缺少首轮评测 ID')
  assert.equal(state.formalInitialEvaluation?.datasetVersion, 'public-tech-2024-v4', '只能使用正式 v4 评测建立 Stable')
  assert.equal(state.formalInitialEvaluation?.gateStatus, 'PASS', '只有正式 v3 的 GATE PASS 才能建立初始 Stable')

  const { browser, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)
  try {
    await gotoRoute(page, '/admin/releases')
    await page.locator('input.text-input').nth(0).fill(candidateId)
    await page.locator('input.text-input').nth(1).fill(evaluationId)
    await page.locator('input.text-input').nth(2).fill('首轮 BASELINE 对照门禁通过，建立初始 Stable')

    const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
      && response.url().endsWith('/api/releases/register'))
    await page.getByRole('button', { name: '注册 Candidate' }).click()
    const response = await responsePromise
    assert.equal(response.status(), 201)
    const release = await response.json()
    assert.equal(release.revision, 1)
    assert.equal(release.action, 'INITIALIZE')
    assert.equal(release.stableVersionId, candidateId)
    assert.equal(release.candidateVersionId, null)
    assert.equal(release.shadowEnabled, false)

    await page.getByText('INITIALIZE', { exact: true }).first().waitFor({ state: 'visible' })
    await page.getByText(candidateId, { exact: true }).waitFor({ state: 'visible' })
    await page.getByText('R1', { exact: true }).waitFor({ state: 'visible' })
    await page.getByText('SHADOW OFF', { exact: true }).waitFor({ state: 'visible' })

    state.initialStable = { versionId: candidateId, release }
    fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`, 'utf8')
    await saveScreenshot(page, 'full-demo-initial-stable.png')
    assertNoDiagnostics(diagnostics, '首个 Candidate 注册并建立初始 Stable')
    console.log(`PASS: ${candidateId} 已通过评测 ${evaluationId} 建立为初始 Stable。`)
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
