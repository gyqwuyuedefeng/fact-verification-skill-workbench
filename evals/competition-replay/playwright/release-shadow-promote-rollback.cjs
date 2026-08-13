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

const projectRoot = path.resolve(__dirname, '..', '..', '..')
const statePath = path.join(ensureEvidenceRoot(), 'full-demo-state.json')
const reportsRoot = path.resolve(__dirname, '..', '..', 'reports')

const shadowCases = [
  {
    file: path.join(projectRoot, 'evals/demo-materials/04-影子灰度-科大讯飞经营事实.md'),
    prompt: '请只核验附件中的营业收入主张，严格对齐主体、2024 年、营业收入口径和万元单位。',
    review: 'PASS',
  },
  {
    file: path.join(projectRoot, 'evals/demo-materials/05-影子灰度-金山办公风险事实.md'),
    prompt: '请只核验附件中的否定性风险主张；查询为空不能证明完全不存在，覆盖不足时要求人工介入。',
    review: 'FAIL',
  },
]

async function releaseAction(page, name, endpoint, expectedStatus = 200) {
  const responsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && response.url().endsWith(endpoint))
  await page.getByRole('button', { name, exact: true }).click()
  const response = await responsePromise
  assert.equal(response.status(), expectedStatus, `${name} 状态码不正确`)
  const result = expectedStatus === 204 ? null : await response.json()
  await page.waitForLoadState('networkidle')
  return result
}

async function runStableAttachment(page, testCase, screenshotName, hiddenCandidateId) {
  await gotoRoute(page, '/verification')
  await page.getByRole('button', { name: /当前 Stable/ }).click()
  await page.locator('input[type="file"]').setInputFiles(testCase.file)
  await page.locator('[data-test="chat-message"]').fill(testCase.prompt)
  const runResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && /\/api\/tasks\/[0-9a-f-]+\/runs$/.test(response.url()))
  await page.locator('[data-test="start-verification"]').click()
  const runResponse = await runResponsePromise
  assert.equal(runResponse.status(), 202)
  const startedTask = await runResponse.json()
  assert.equal(startedTask.executionMode, 'STABLE')
  assert(startedTask.primaryRunId)

  const card = page.locator('.conversation-card').first()
  const status = card.locator('header .gate-chip')
  await page.waitForFunction(
    (element) => ['COMPLETED', 'FAILED', 'PARTIAL'].includes(element.textContent?.trim() || ''),
    await status.elementHandle(),
    { timeout: 300_000 },
  )
  assert.equal((await status.innerText()).trim(), 'COMPLETED', await card.innerText())
  assert.equal(await card.locator('.claim-card').count() >= 1, true)
  assert.match(await card.locator('.live-timeline').innerText(), /RUN_COMPLETED/)
  const formalCardText = await card.innerText()
  assert.doesNotMatch(formalCardText, /SHADOW/, '普通用户的正式结果卡片不得暴露影子运行')
  assert.equal(
    formalCardText.includes(hiddenCandidateId),
    false,
    '普通用户的正式结果卡片不得暴露 Candidate 版本',
  )
  await saveScreenshot(page, screenshotName)
  return startedTask
}

async function waitForShadowRuns(context, candidateId, minimum) {
  for (let attempt = 0; attempt < 240; attempt += 1) {
    const response = await context.request.get(
      `${baseUrl}/api/shadow-runs?versionId=${encodeURIComponent(candidateId)}`,
    )
    assert.equal(response.status(), 200)
    const history = await response.json()
    const completed = history.items.filter((item) => item.shadowStatus === 'COMPLETED')
    if (completed.length >= minimum) return history
    await new Promise((resolve) => setTimeout(resolve, 750))
  }
  throw new Error('影子运行未在 180 秒内全部完成')
}

async function main() {
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'))
  const oldStableId = state.initialStable?.versionId
  const candidateId = state.optimizedCandidate?.id
  const evaluationId = state.formalThreeEvaluation?.id
  assert(oldStableId && candidateId && evaluationId, '发布流程缺少 Stable、Candidate 或通过评测')
  assert.equal(state.formalThreeEvaluation.gateStatus, 'PASS')

  const { browser, context, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)
  try {
    await gotoRoute(page, '/admin/releases')
    await page.locator('input.text-input').nth(0).fill(candidateId)
    await page.locator('input.text-input').nth(1).fill(evaluationId)
    await page.locator('input.text-input').nth(2).fill('三版本同条件评测通过，进入真实材料影子观察')
    const currentBeforeResponse = await context.request.get(`${baseUrl}/api/releases/current`)
    assert.equal(currentBeforeResponse.status(), 200)
    const currentBefore = await currentBeforeResponse.json()
    if (currentBefore.action === 'ROLLBACK'
      && currentBefore.stableVersionId === oldStableId
      && currentBefore.previousStableVersionId === candidateId) {
      const history = await (await context.request.get(`${baseUrl}/api/releases/history`)).json()
      assert.deepEqual(
        history.slice(0, 7).map((item) => item.action),
        ['ROLLBACK', 'PROMOTE', 'SHADOW_START', 'SHADOW_STOP', 'SHADOW_START', 'REGISTER', 'INITIALIZE'],
      )
      assert(state.releaseEvidence, '回滚状态必须关联已经导出的发布证据')
      await page.getByText('ROLLBACK', { exact: true }).first().waitFor({ state: 'visible' })
      await saveScreenshot(page, 'full-demo-release-rollback-final.png')
      assertNoDiagnostics(diagnostics, '已完成发布链路的最终状态复核')
      console.log('PASS: 已完成发布链路的最终 Stable、回滚目标、七步追加历史和导出证据均一致。')
      return
    }
    const registered = currentBefore.candidateVersionId === candidateId
      ? currentBefore
      : await releaseAction(page, '注册 Candidate', '/api/releases/register', 201)
    assert.equal(['REGISTER', 'SHADOW_START'].includes(registered.action), true)
    assert.equal(registered.stableVersionId, oldStableId)
    assert.equal(registered.candidateVersionId, candidateId)

    let shadowHistory = await (await context.request.get(
      `${baseUrl}/api/shadow-runs?versionId=${encodeURIComponent(candidateId)}`,
    )).json()
    if (shadowHistory.summary.pass === 0) {
      const expectedDiagnosticOffset = diagnostics.length
      await page.locator('input.text-input').nth(2).fill('验证没有影子 PASS 时禁止晋升')
      await releaseAction(page, '晋升 Stable', '/api/releases/promote', 400)
      await page.getByText('Candidate 至少需要一条影子复核 PASS').waitFor({ state: 'visible' })
      assert.equal(
        diagnostics.slice(expectedDiagnosticOffset).some((item) => item.includes('400 POST') && item.includes('/api/releases/promote')),
        true,
        '被门禁拒绝的晋升必须留下明确 HTTP 400 和稳定业务错误证据',
      )
      diagnostics.splice(expectedDiagnosticOffset)
    }

    const currentRelease = await (await context.request.get(`${baseUrl}/api/releases/current`)).json()
    await page.locator('input.text-input').nth(2).fill('开启后台 Candidate 影子，不改变用户 Stable 正式答案')
    const shadowStarted = currentRelease.shadowEnabled
      ? currentRelease
      : await releaseAction(page, '开启影子', '/api/releases/shadow/start')
    assert.equal(shadowStarted.shadowEnabled, true)
    assert.equal(shadowStarted.action, 'SHADOW_START')

    const shadowTasks = []
    for (const [index, testCase] of shadowCases.entries()) {
      const existing = shadowHistory.items.find((item) => item.fileName === path.basename(testCase.file)
        && item.shadowStatus === 'COMPLETED')
      shadowTasks.push(existing
        ? { id: existing.taskId, primaryRunId: existing.primaryRunId }
        : await runStableAttachment(
          page,
          testCase,
          `full-demo-shadow-primary-${index + 1}.png`,
          candidateId,
        ))
    }
    shadowHistory = await waitForShadowRuns(context, candidateId, 2)
    assert.equal(shadowHistory.items.every((item) => item.stableVersionId === oldStableId), true)
    assert.equal(shadowHistory.items.every((item) => item.candidateVersionId === candidateId), true)

    await gotoRoute(page, '/admin/releases')
    for (const testCase of shadowCases) {
      const fileName = path.basename(testCase.file)
      const row = page.locator('.shadow-run-row').filter({ hasText: fileName })
      await row.waitFor({ state: 'visible' })
      const item = shadowHistory.items.find((candidate) => candidate.fileName === fileName)
      assert(item, `影子历史缺少 ${fileName}`)
      if (item.reviewStatus !== 'PENDING') {
        assert.equal(item.reviewStatus, testCase.review, `${fileName} 已有复核结果与预期不一致`)
        continue
      }
      const reviewResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
        && response.url().endsWith(`/api/runs/${item.shadowRunId}/review`))
      await row.getByRole('button', { name: testCase.review, exact: true }).click()
      const reviewResponse = await reviewResponsePromise
      assert.equal(reviewResponse.status(), 201)
      await row.getByText(testCase.review, { exact: true }).waitFor({ state: 'visible' })
    }

    await page.locator('.shadow-filters input').nth(0).fill('04-影子灰度')
    assert.equal(await page.locator('.shadow-run-row').count(), 1)
    await page.locator('.shadow-filters input').nth(0).fill('')
    await page.locator('.shadow-filters select').selectOption('PASS')
    assert.equal(await page.locator('.shadow-run-row').count(), 1)
    await page.locator('.shadow-filters select').selectOption('')
    await page.locator('.shadow-filters input').nth(1).fill(candidateId.slice(0, 8))
    assert.equal(await page.locator('.shadow-run-row').count() >= 2, true)
    await page.locator('.shadow-filters input').nth(1).fill('')

    shadowHistory = await (await context.request.get(
      `${baseUrl}/api/shadow-runs?versionId=${encodeURIComponent(candidateId)}`,
    )).json()
    assert.equal(shadowHistory.summary.pass >= 1, true)
    assert.equal(shadowHistory.summary.fail >= 1, true)

    await page.locator('input.text-input').nth(2).fill('验证停止影子后不再创建后台运行')
    const shadowStopped = await releaseAction(page, '停止影子', '/api/releases/shadow/stop')
    assert.equal(shadowStopped.shadowEnabled, false)
    assert.equal(shadowStopped.action, 'SHADOW_STOP')

    await page.locator('input.text-input').nth(2).fill('复演前重新开启影子并保留已审核历史')
    const shadowRestarted = await releaseAction(page, '开启影子', '/api/releases/shadow/start')
    assert.equal(shadowRestarted.shadowEnabled, true)

    await page.locator('input.text-input').nth(2).fill('离线门禁通过且真实材料已有人工 PASS，晋升新 Stable')
    const promoted = await releaseAction(page, '晋升 Stable', '/api/releases/promote')
    assert.equal(promoted.action, 'PROMOTE')
    assert.equal(promoted.stableVersionId, candidateId)
    assert.equal(promoted.previousStableVersionId, oldStableId)
    assert.equal(promoted.candidateVersionId, null)
    assert.equal(promoted.shadowEnabled, false)

    const postPromotionTask = await runStableAttachment(
      page,
      shadowCases[0],
      'full-demo-new-stable-task.png',
      oldStableId,
    )

    await gotoRoute(page, '/admin/releases')
    await page.locator('input.text-input').nth(2).fill('现场证明可一键恢复上一版 Stable')
    const rolledBack = await releaseAction(page, '回滚上一版', '/api/releases/rollback')
    assert.equal(rolledBack.action, 'ROLLBACK')
    assert.equal(rolledBack.stableVersionId, oldStableId)
    assert.equal(rolledBack.previousStableVersionId, candidateId)

    const postRollbackTask = await runStableAttachment(
      page,
      shadowCases[1],
      'full-demo-post-rollback-task.png',
      candidateId,
    )

    const [currentResponse, historyResponse] = await Promise.all([
      context.request.get(`${baseUrl}/api/releases/current`),
      context.request.get(`${baseUrl}/api/releases/history`),
    ])
    assert.equal(currentResponse.status(), 200)
    assert.equal(historyResponse.status(), 200)
    const current = await currentResponse.json()
    const history = await historyResponse.json()
    assert.equal(current.action, 'ROLLBACK')
    assert.deepEqual(
      history.slice(0, 7).map((item) => item.action),
      ['ROLLBACK', 'PROMOTE', 'SHADOW_START', 'SHADOW_STOP', 'SHADOW_START', 'REGISTER', 'INITIALIZE'],
    )

    const evidence = {
      generatedAt: new Date().toISOString(),
      evaluationId,
      oldStableId,
      candidateId,
      shadowSummary: shadowHistory.summary,
      shadowTasks: shadowTasks.map((task) => ({ taskId: task.id, primaryRunId: task.primaryRunId })),
      postPromotionTask: { taskId: postPromotionTask.id, primaryRunId: postPromotionTask.primaryRunId },
      postRollbackTask: { taskId: postRollbackTask.id, primaryRunId: postRollbackTask.primaryRunId },
      current,
      history,
    }
    fs.mkdirSync(reportsRoot, { recursive: true })
    fs.writeFileSync(path.join(reportsRoot, 'release-evidence.json'), `${JSON.stringify(evidence, null, 2)}\n`)
    fs.writeFileSync(
      path.join(reportsRoot, 'release-evidence.md'),
      `# 影子灰度、晋升与回滚证据\n\n- 同条件评测：${evaluationId}\n- 原 Stable：${oldStableId}\n- Candidate：${candidateId}\n- 影子人工 PASS：${shadowHistory.summary.pass}\n- 影子人工 FAIL：${shadowHistory.summary.fail}\n- 晋升后新任务 PRIMARY：${postPromotionTask.primaryRunId}\n- 回滚后新任务 PRIMARY：${postRollbackTask.primaryRunId}\n- 最终状态：${current.action}，Stable 已恢复为 ${current.stableVersionId}\n\n完整追加历史及任务标识见同目录 \`release-evidence.json\`。\n`,
      'utf8',
    )
    state.releaseEvidence = evidence
    fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`)

    await gotoRoute(page, '/admin/releases')
    await page.getByText('ROLLBACK', { exact: true }).first().waitFor({ state: 'visible' })
    await saveScreenshot(page, 'full-demo-release-rollback-final.png')
    assertNoDiagnostics(diagnostics, '注册、门禁拒绝、影子启停、PASS/FAIL、晋升、新任务和回滚')
    console.log('PASS: Candidate 注册、影子启停、人工 PASS/FAIL、晋升、新 Stable 生效和回滚全链路正常。')
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
