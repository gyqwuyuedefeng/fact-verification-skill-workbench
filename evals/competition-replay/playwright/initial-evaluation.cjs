const fs = require('node:fs')
const path = require('node:path')

const {
  assert,
  baseUrl,
  collectPageDiagnostics,
  ensureEvidenceRoot,
  gotoRoute,
  openBrowser,
  replayRoot,
  saveScreenshot,
  assertNoDiagnostics,
} = require('./helpers.cjs')

const reportsRoot = path.resolve(replayRoot, '..', 'reports')
const statePath = path.join(ensureEvidenceRoot(), 'full-demo-state.json')
const formalDatasetVersion = 'public-tech-2024-v3'

function readState() {
  assert.equal(fs.existsSync(statePath), true, '请先运行 skill-lifecycle.cjs 生成首个 Candidate')
  return JSON.parse(fs.readFileSync(statePath, 'utf8'))
}

function writeState(state) {
  fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`, 'utf8')
}

/**
 * 将上一轮真实但门禁失败的正式批次移入 rejected 历史，再允许创建新批次。
 * 该分支只由显式 EVALUATION_RERUN=1 开启，避免普通复演误覆盖已经完成的正式证据。
 */
function archiveFailedFormalEvaluationForRerun(state) {
  if (process.env.EVALUATION_RERUN !== '1' || !state.formalInitialEvaluationId) return
  assert.equal(
    state.formalInitialEvaluation?.gateStatus,
    'FAIL',
    '只允许自动归档 GATE FAIL；GATE PASS 批次必须保留并继续建立 Stable',
  )
  const evaluationId = state.formalInitialEvaluationId
  const archiveRoot = path.join(reportsRoot, 'rejected', `${evaluationId.slice(0, 8)}-pre-tool-choice-runtime`)
  fs.mkdirSync(archiveRoot, { recursive: true })
  for (const fileName of [
    'initial-evaluation-report.md',
    'initial-evaluation-report.json',
    'initial-run-manifest.json',
    'initial-failed-samples.json',
    'initial-version-card.json',
  ]) {
    const source = path.join(reportsRoot, fileName)
    if (fs.existsSync(source)) fs.copyFileSync(source, path.join(archiveRoot, fileName))
  }
  fs.copyFileSync(statePath, path.join(archiveRoot, 'full-demo-state-before-rerun.json'))
  state.rejectedPreToolChoiceEvaluationId = evaluationId
  state.rejectedPreToolChoiceEvaluationReason =
    '旧批次仍使用动态 Skill 决策且模型可跳过主体工具；已改为冻结 Skill 确定性注入，并用 AgentScope 原生 tool_choice 强制首轮 resolve_company。旧批次只作失败历史，不用于 Stable。'
  state.rejectedPreToolChoiceEvaluationArchive = path.relative(replayRoot, archiveRoot)
  delete state.formalInitialEvaluationId
  delete state.formalInitialEvaluation
  writeState(state)
}

async function waitForEvaluation(page, evaluationId) {
  const startedAt = Date.now()
  let lastStatus = 'PENDING'
  while (Date.now() - startedAt < 180 * 60 * 1000) {
    const responsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
      && response.url().endsWith(`/api/evaluations/${evaluationId}`))
    await page.getByRole('button', { name: '刷新评测状态' }).click()
    const response = await responsePromise
    assert.equal(response.status(), 200)
    const evaluation = await response.json()
    lastStatus = evaluation.status
    const elapsed = Math.floor((Date.now() - startedAt) / 1000)
    console.log(`PROGRESS: evaluation=${evaluationId} status=${evaluation.status} elapsed=${elapsed}s`)
    if (evaluation.status === 'COMPLETED') return evaluation
    if (evaluation.status === 'FAILED' || evaluation.status === 'INTERRUPTED') {
      throw new Error(`真实评测异常结束：${evaluation.status}`)
    }
    await page.waitForTimeout(5000)
  }
  throw new Error(`真实评测 180 分钟内未完成，最后状态：${lastStatus}`)
}

async function main() {
  const state = readState()
  archiveFailedFormalEvaluationForRerun(state)
  const candidate = state.bootstrapCandidate ?? state.initialCandidate
  assert(candidate?.id, '测试状态缺少 initialCandidate.id')

  const { browser, context, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)

  try {
    await gotoRoute(page, '/admin/evaluations')
    assert.equal(await page.locator('#dataset-version').inputValue(), formalDatasetVersion)
    await page.locator('[data-test="initial-stable"]').check()
    await page.locator('[data-test="candidate-id"]').fill(candidate.id)
    assert.equal(await page.locator('[data-test="stable-id"]').isDisabled(), true)
    assert.equal(await page.locator('[data-test="start-evaluation"]').isEnabled(), true)
    assert.match(await page.locator('[data-test="start-evaluation"]').textContent(), /BASELINE \+ 首个 Candidate/)

    let evaluation
    if (state.formalInitialEvaluationId) {
      const response = await context.request.get(`${baseUrl}/api/evaluations/${state.formalInitialEvaluationId}`)
      assert.equal(response.status(), 200)
      evaluation = await response.json()
      assert.equal(evaluation.datasetVersion, formalDatasetVersion, '只能恢复正式 v3 评测')
      if (evaluation.status !== 'COMPLETED') {
        await page.locator('button.history-row').filter({ hasText: state.formalInitialEvaluationId.slice(0, 8) }).click()
      }
    } else {
      const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
        && /\/api\/evaluations$/.test(response.url()))
      await page.locator('[data-test="start-evaluation"]').click()
      const response = await responsePromise
      assert.equal(response.status(), 202)
      evaluation = await response.json()
      assert.match(evaluation.id, /^[0-9a-f-]{36}$/i)
      state.formalInitialEvaluationId = evaluation.id
      writeState(state)
    }

    if (evaluation.status !== 'COMPLETED') {
      evaluation = await waitForEvaluation(page, evaluation.id)
    } else {
      await page.locator('button.history-row').filter({ hasText: evaluation.id.slice(0, 8) }).click()
    }

    assert.equal(evaluation.sampleCount, 30)
    assert.equal(evaluation.datasetVersion, formalDatasetVersion)
    assert.equal(evaluation.status, 'COMPLETED')
    assert.equal(evaluation.variants.length, 2)
    assert.equal(evaluation.variants[0].identifier, 'BASELINE')
    assert.equal(evaluation.variants[1].identifier, candidate.id)
    assert.match(evaluation.datasetHash, /^[0-9a-f]{64}$/)
    for (const key of [
      'modelConfigHash',
      'toolContractHash',
      'evidenceSnapshotHash',
      'outputSchemaHash',
    ]) {
      assert.match(evaluation.runManifest[key], /^[0-9a-f]{64}$/, `${key} 必须锁定为 SHA-256`)
    }
    assert.deepEqual(evaluation.runManifest.modelParameters, {
      temperature: 0,
      topP: 1,
      seed: 20260812,
      parallelToolCalls: false,
      maxTokens: 8192,
      enableThinking: false,
    }, '正式评测必须显式锁定模型采样参数，不能依赖服务端默认值')

    await page.waitForFunction(() => document.querySelectorAll('.sample-drilldown').length === 30)
    assert.equal(await page.locator('.metric-row:not(.metric-head)').count(), 2)
    assert.equal(await page.locator('.sample-drilldown').count(), 30)
    const firstSample = page.locator('.sample-drilldown').first()
    await firstSample.locator(':scope > summary').click()
    assert.equal(await firstSample.locator('.sample-variant-result').count(), 2)
    await firstSample.getByText('查看原始输出').first().click()
    assert.match(
      await firstSample.locator('.raw-attempt pre').first().innerText(),
      /"claims"|null/,
      '原始输出必须忠实显示结构化结果或失败时的 null，不能隐藏失败尝试',
    )
    await page.getByText(`GATE ${evaluation.gateStatus}`, { exact: true }).waitFor({ state: 'visible' })

    const markdownLink = page.getByRole('link', { name: '导出报告 Markdown' })
    const jsonLink = page.getByRole('link', { name: '导出报告 JSON' })
    const markdownHref = await markdownLink.getAttribute('href')
    const jsonHref = await jsonLink.getAttribute('href')
    assert(markdownHref)
    assert(jsonHref)
    const [markdownResponse, jsonResponse, samplesResponse, cardResponse] = await Promise.all([
      context.request.get(new URL(markdownHref, baseUrl).toString()),
      context.request.get(new URL(jsonHref, baseUrl).toString()),
      context.request.get(`${baseUrl}/api/evaluations/${evaluation.id}/samples`),
      context.request.get(`${baseUrl}/api/skills/company-material-fact-check/versions/${candidate.id}/card`),
    ])
    for (const response of [markdownResponse, jsonResponse, samplesResponse, cardResponse]) {
      assert.equal(response.status(), 200, `导出或证据接口失败：${response.url()}`)
    }
    const reportJson = await jsonResponse.json()
    const samples = await samplesResponse.json()
    const versionCard = await cardResponse.json()
    assert.equal(reportJson.evaluationRunId, evaluation.id)
    assert.equal(reportJson.sampleCount, 30)
    assert.equal(samples.length, 30)

    fs.mkdirSync(reportsRoot, { recursive: true })
    fs.writeFileSync(path.join(reportsRoot, 'initial-evaluation-report.md'), await markdownResponse.text(), 'utf8')
    fs.writeFileSync(path.join(reportsRoot, 'initial-evaluation-report.json'), `${JSON.stringify(reportJson, null, 2)}\n`, 'utf8')
    fs.writeFileSync(path.join(reportsRoot, 'initial-run-manifest.json'), `${JSON.stringify(evaluation.runManifest, null, 2)}\n`, 'utf8')
    fs.writeFileSync(
      path.join(reportsRoot, 'initial-failed-samples.json'),
      `${JSON.stringify(samples.filter((sample) => {
        const result = sample.variantResults?.[candidate.id]
        return !result?.score?.accurate || result.attempts?.some((attempt) => attempt.errorCode)
      }), null, 2)}\n`,
      'utf8',
    )
    fs.writeFileSync(path.join(reportsRoot, 'initial-version-card.json'), `${JSON.stringify(versionCard, null, 2)}\n`, 'utf8')

    await page.locator('[data-test="evaluation-tab-version"]').click()
    await page.locator('.inline-query input').fill(candidate.id)
    const [summaryResponse] = await Promise.all([
      page.waitForResponse((response) => response.url().endsWith(`/api/evaluations/version-summary/${candidate.id}`)),
      page.getByRole('button', { name: '汇总', exact: true }).click(),
    ])
    assert.equal(summaryResponse.status(), 200)
    const summary = await summaryResponse.json()
    assert.equal(summary.evaluationCount >= 1, true)
    assert.equal(summary.latestEvaluationId, evaluation.id)
    await page.getByText('参评次数').waitFor({ state: 'visible' })

    state.formalInitialEvaluation = {
      id: evaluation.id,
      datasetVersion: evaluation.datasetVersion,
      gateStatus: evaluation.gateStatus,
      metrics: evaluation.metrics,
      versionCard,
    }
    writeState(state)
    await saveScreenshot(page, 'full-demo-initial-evaluation-summary.png')
    assertNoDiagnostics(diagnostics, '首个 Candidate 的 30 条真实同条件评测')

    console.log(`PASS: ${formalDatasetVersion} 的 30 条 BASELINE + Candidate 评测 ${evaluation.id} 完成，GATE ${evaluation.gateStatus}。`)
    if (evaluation.gateStatus !== 'PASS') {
      process.exitCode = 2
    }
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
