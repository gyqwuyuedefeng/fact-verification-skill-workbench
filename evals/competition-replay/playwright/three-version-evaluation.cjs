const fs = require('node:fs')
const path = require('node:path')

const {
  assert,
  baseUrl,
  collectPageDiagnostics,
  ensureEvidenceRoot,
  gotoRoute,
  openBrowser,
  saveScreenshot,
  assertNoDiagnostics,
} = require('./helpers.cjs')

const statePath = path.join(ensureEvidenceRoot(), 'full-demo-state.json')
const reportsRoot = path.resolve(__dirname, '..', '..', 'reports')

async function waitForEvaluation(page, evaluationId) {
  const startedAt = Date.now()
  while (Date.now() - startedAt < 180 * 60 * 1000) {
    const responsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
      && response.url().endsWith(`/api/evaluations/${evaluationId}`))
    await page.getByRole('button', { name: '刷新评测状态' }).click()
    const response = await responsePromise
    assert.equal(response.status(), 200)
    const evaluation = await response.json()
    console.log(`PROGRESS: evaluation=${evaluationId} status=${evaluation.status} elapsed=${Math.floor((Date.now() - startedAt) / 1000)}s`)
    if (evaluation.status === 'COMPLETED') return evaluation
    if (['FAILED', 'INTERRUPTED'].includes(evaluation.status)) {
      throw new Error(`三版本评测异常结束：${evaluation.status}`)
    }
    await page.waitForTimeout(5000)
  }
  throw new Error('三版本评测 180 分钟内未完成')
}

async function main() {
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'))
  if (process.env.EVALUATION_RERUN === '1' && state.formalThreeEvaluationId) {
    const previousId = state.formalThreeEvaluationId
    const historyRoot = path.join(reportsRoot, 'historical', `${previousId.slice(0, 8)}-pre-result-schema-retry`)
    fs.mkdirSync(historyRoot, { recursive: true })
    for (const name of [
      'evaluation-report.md',
      'evaluation-report.json',
      'run-manifest.json',
      'failed-samples.json',
      'stable-version-card.json',
      'candidate-version-card.json',
    ]) {
      const source = path.join(reportsRoot, name)
      if (fs.existsSync(source)) fs.copyFileSync(source, path.join(historyRoot, name))
    }
    state.previousFormalThreeEvaluationId = previousId
    state.previousFormalThreeEvaluationReason = 'Agent 运行时新增一次结构化输出纠正重试，运行时 hash 已变化；旧 PASS 只作历史，重新执行同条件评测。'
    delete state.formalThreeEvaluationId
    delete state.formalThreeEvaluation
    fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`)
  }
  const stableId = state.initialStable?.versionId
  const candidateId = state.optimizedCandidate?.id
  assert(stableId, '缺少初始 Stable')
  assert(candidateId, '缺少优化 Candidate')

  const { browser, context, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)
  try {
    await gotoRoute(page, '/admin/evaluations')
    assert.equal(await page.locator('[data-test="initial-stable"]').isChecked(), false)
    assert.equal(await page.locator('[data-test="start-evaluation"]').isDisabled(), true)
    await page.locator('[data-test="stable-id"]').fill(stableId)
    await page.locator('[data-test="candidate-id"]').fill(candidateId)
    assert.match(await page.locator('[data-test="start-evaluation"]').innerText(), /BASELINE \+ Stable \+ Candidate/)

    let evaluation
    if (state.formalThreeEvaluationId) {
      const response = await context.request.get(`${baseUrl}/api/evaluations/${state.formalThreeEvaluationId}`)
      assert.equal(response.status(), 200)
      evaluation = await response.json()
      if (evaluation.status !== 'COMPLETED') {
        await page.locator('button.history-row').filter({ hasText: evaluation.id.slice(0, 8) }).click()
      }
    } else {
      const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
        && /\/api\/evaluations$/.test(response.url()))
      await page.locator('[data-test="start-evaluation"]').click()
      const response = await responsePromise
      assert.equal(response.status(), 202)
      evaluation = await response.json()
      state.formalThreeEvaluationId = evaluation.id
      fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`)
    }

    if (evaluation.status !== 'COMPLETED') {
      evaluation = await waitForEvaluation(page, evaluation.id)
    } else {
      await page.locator('button.history-row').filter({ hasText: evaluation.id.slice(0, 8) }).click()
    }

    assert.equal(evaluation.datasetVersion, 'public-tech-2024-v3')
    assert.equal(evaluation.sampleCount, 30)
    assert.deepEqual(evaluation.variants.map((item) => item.identifier), ['BASELINE', stableId, candidateId])
    assert.deepEqual(evaluation.runManifest.modelParameters, {
      temperature: 0,
      topP: 1,
      seed: 20260812,
      parallelToolCalls: false,
      maxTokens: 8192,
      enableThinking: false,
    })
    assert.equal(Object.keys(evaluation.metrics).length, 3)
    assert.equal(evaluation.gateStatus, 'PASS', `优化 Candidate 必须通过门禁：${JSON.stringify(evaluation.gateReasons)}`)
    await page.getByText('GATE PASS', { exact: true }).waitFor({ state: 'visible' })
    await page.waitForFunction(() => document.querySelectorAll('.sample-drilldown').length === 30)
    assert.equal(await page.locator('.metric-row:not(.metric-head)').count(), 3)

    const markdownHref = await page.getByRole('link', { name: '导出报告 Markdown' }).getAttribute('href')
    const jsonHref = await page.getByRole('link', { name: '导出报告 JSON' }).getAttribute('href')
    assert(markdownHref && jsonHref)
    const [markdownResponse, jsonResponse, samplesResponse, stableCardResponse, candidateCardResponse] = await Promise.all([
      context.request.get(new URL(markdownHref, baseUrl).toString()),
      context.request.get(new URL(jsonHref, baseUrl).toString()),
      context.request.get(`${baseUrl}/api/evaluations/${evaluation.id}/samples`),
      context.request.get(`${baseUrl}/api/skills/company-material-fact-check/versions/${stableId}/card`),
      context.request.get(`${baseUrl}/api/skills/company-material-fact-check/versions/${candidateId}/card`),
    ])
    for (const response of [markdownResponse, jsonResponse, samplesResponse, stableCardResponse, candidateCardResponse]) {
      assert.equal(response.status(), 200, `报告证据请求失败：${response.url()}`)
    }
    const reportJson = await jsonResponse.json()
    const samples = await samplesResponse.json()
    const stableCard = await stableCardResponse.json()
    const candidateCard = await candidateCardResponse.json()
    fs.mkdirSync(reportsRoot, { recursive: true })
    fs.writeFileSync(path.join(reportsRoot, 'evaluation-report.md'), await markdownResponse.text(), 'utf8')
    fs.writeFileSync(path.join(reportsRoot, 'evaluation-report.json'), `${JSON.stringify(reportJson, null, 2)}\n`)
    fs.writeFileSync(path.join(reportsRoot, 'run-manifest.json'), `${JSON.stringify(evaluation.runManifest, null, 2)}\n`)
    fs.writeFileSync(
      path.join(reportsRoot, 'failed-samples.json'),
      `${JSON.stringify(samples.filter((sample) => !sample.variantResults[candidateId]?.score?.accurate), null, 2)}\n`,
    )
    fs.writeFileSync(path.join(reportsRoot, 'stable-version-card.json'), `${JSON.stringify(stableCard, null, 2)}\n`)
    fs.writeFileSync(path.join(reportsRoot, 'candidate-version-card.json'), `${JSON.stringify(candidateCard, null, 2)}\n`)

    await page.locator('[data-test="evaluation-tab-version"]').click()
    const summaryInput = page.locator('.inline-query input')
    await summaryInput.fill(stableId)
    await Promise.all([
      page.waitForResponse((response) => response.url().endsWith(`/api/evaluations/version-summary/${stableId}`)),
      page.getByRole('button', { name: '汇总', exact: true }).click(),
    ])
    await page.getByText('参评次数').waitFor({ state: 'visible' })
    assert.equal(Number(await page.locator('.summary-strip strong').first().innerText()) >= 2, true, 'Stable 应关联首轮和三版本评测')

    await summaryInput.fill(candidateId)
    await Promise.all([
      page.waitForResponse((response) => response.url().endsWith(`/api/evaluations/version-summary/${candidateId}`)),
      page.getByRole('button', { name: '汇总', exact: true }).click(),
    ])
    await page.locator('.summary-strip').getByText(evaluation.id, { exact: true }).waitFor({ state: 'visible' })

    await page.locator('[data-test="evaluation-tab-compare"]').click()
    await page.locator('.compare-query input').nth(0).fill(stableId)
    await page.locator('.compare-query input').nth(1).fill(candidateId)
    const [comparisonResponse] = await Promise.all([
      page.waitForResponse((response) => response.url().includes('/api/evaluations/comparison?')),
      page.getByRole('button', { name: '对比', exact: true }).click(),
    ])
    assert.equal(comparisonResponse.status(), 200)
    const comparison = await comparisonResponse.json()
    assert.equal(comparison.comparable, true)
    assert.equal(comparison.evaluationRunId, evaluation.id)
    await page.getByText('目标版胜').waitFor({ state: 'visible' })

    await page.locator('[data-test="evaluation-tab-runs"]').click()
    const initialEvaluationId = state.formalInitialEvaluation?.id
    assert(initialEvaluationId)
    await page.locator('button.history-row').filter({ hasText: initialEvaluationId.slice(0, 8) }).click()
    await page.getByText(initialEvaluationId.slice(0, 8), { exact: true }).waitFor({ state: 'visible' })
    await page.locator('button.history-row').filter({ hasText: evaluation.id.slice(0, 8) }).click()
    await page.getByText('GATE PASS', { exact: true }).waitFor({ state: 'visible' })

    state.formalThreeEvaluation = {
      id: evaluation.id,
      gateStatus: evaluation.gateStatus,
      metrics: evaluation.metrics,
      comparison,
    }
    fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`)
    await saveScreenshot(page, 'full-demo-three-version-evaluation.png')
    assertNoDiagnostics(diagnostics, '三版本评测、历史、版本汇总、直接对比和报告导出')
    console.log(`PASS: 三版本 30 条评测 ${evaluation.id} GATE PASS，历史、汇总、对比和报告导出正常。`)
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
