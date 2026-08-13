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

const projectRoot = path.resolve(__dirname, '..', '..', '..')

async function waitForJsonResponse(page, predicate, action, expectedStatus) {
  const responsePromise = page.waitForResponse((response) => predicate(response))
  await action()
  const response = await responsePromise
  assert.equal(response.status(), expectedStatus, `${response.request().method()} ${response.url()} 状态码错误`)
  return response.json()
}

async function main() {
  const { browser, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)

  try {
    await gotoRoute(page, '/admin/skills')

    const createButton = page.locator('[data-test="create-draft"]')
    await createButton.waitFor({ state: 'visible' })
    const existingVersions = await page.evaluate(async () => {
      const response = await fetch('/api/skills/company-material-fact-check/versions')
      if (!response.ok) throw new Error(`版本清单请求失败：${response.status}`)
      return response.json()
    })

    let draftId
    let candidate
    let initialSkill
    if (existingVersions.length === 0) {
      assert.equal(await createButton.textContent().then((value) => value.trim()), '新建初始 DRAFT')
      assert.equal(await createButton.isEnabled(), true, '空版本库必须允许创建初始 DRAFT')

      const draft = await waitForJsonResponse(
        page,
        (response) => response.request().method() === 'POST'
          && /\/api\/skills\/company-material-fact-check\/drafts$/.test(response.url()),
        () => createButton.click(),
        201,
      )

      assert.match(draft.id, /^[0-9a-f-]{36}$/i)
      assert.equal(draft.status, 'DRAFT')
      assert.equal(draft.parentVersionId, null)
      draftId = draft.id

      const skillEditor = page.locator('textarea.code-editor').first()
      const referencesEditor = page.locator('textarea.code-editor.small')
      await page.waitForFunction(() => {
        const editor = document.querySelector('textarea.code-editor')
        return Boolean(editor && editor.value.includes('company-material-fact-check'))
      })

      initialSkill = await skillEditor.inputValue()
      const initialReferences = await referencesEditor.inputValue()
      assert.match(initialSkill, /company-material-fact-check/)
      assert.match(initialSkill, /resolve_company/)
      assert.match(initialReferences, /claim-normalization\.md/)
      assert.equal(await createButton.isDisabled(), true, '已有 DRAFT 时不得重复创建草稿')

      await referencesEditor.fill('{')
      await page.getByRole('button', { name: '保存 DRAFT' }).click()
      await page.getByText('references 必须是合法 JSON 数组').waitFor({ state: 'visible' })

      await referencesEditor.fill(initialReferences)
      await page.locator('input.text-input').first().fill('初始专用事实核验规则')

      const updatedDraft = await waitForJsonResponse(
        page,
        (response) => response.request().method() === 'PUT'
          && response.url().endsWith(`/api/skills/company-material-fact-check/drafts/${draft.id}`),
        () => page.getByRole('button', { name: '保存 DRAFT' }).click(),
        200,
      )
      assert.equal(updatedDraft.id, draft.id)
      assert.equal(updatedDraft.changeSummary, '初始专用事实核验规则')
      await page.getByText('初始专用事实核验规则').last().waitFor({ state: 'visible' })

      candidate = await waitForJsonResponse(
        page,
        (response) => response.request().method() === 'POST'
          && response.url().endsWith(`/api/skills/company-material-fact-check/drafts/${draft.id}/freeze`),
        () => page.locator('[data-test="freeze-draft"]').click(),
        201,
      )
    } else {
      candidate = existingVersions.find((version) => version.status === 'CANDIDATE' && version.parentVersionId === null)
      assert(candidate, '恢复执行时必须找到首个根 Candidate')
      draftId = candidate.id
      assert.equal(await createButton.isDisabled(), true, '已有冻结版本但未选择时不得重复建立根草稿')
      assert.match(await createButton.textContent().then((value) => value.trim()), /请先选择一个版本/)
      await page.locator('button.version-row').filter({ hasText: candidate.version }).click()
      const frozenSkillPath = path.join(
        projectRoot,
        'data',
        'skill-snapshots',
        candidate.id,
        'company-material-fact-check',
        'SKILL.md',
      )
      initialSkill = fs.readFileSync(frozenSkillPath, 'utf8')
    }

    assert.equal(candidate.status, 'CANDIDATE')
    assert.match(candidate.version, /^v\d+\.\d+\.\d+\+[0-9a-f]{12}$/)
    assert.match(candidate.contentHash, /^[0-9a-f]{64}$/)

    await page.getByText(candidate.version, { exact: true }).waitFor({ state: 'visible' })
    await page.getByText('GATE PENDING', { exact: true }).waitFor({ state: 'visible' })
    await page.getByText('完成同条件评测后才能注册', { exact: true }).waitFor({ state: 'visible' })
    await page.getByText(candidate.contentHash, { exact: true }).waitFor({ state: 'visible' })
    assert.equal(await page.locator('[data-test="freeze-draft"]').isDisabled(), true)

    const snapshotRoot = path.join(
      projectRoot,
      'data',
      'skill-snapshots',
      candidate.id,
      'company-material-fact-check',
    )
    const runtimeRoot = path.join(
      projectRoot,
      'data',
      'skill-runtime',
      candidate.id,
      'company-material-fact-check',
    )
    for (const frozenRoot of [snapshotRoot, runtimeRoot]) {
      assert.equal(fs.existsSync(path.join(frozenRoot, 'SKILL.md')), true, `${frozenRoot} 必须包含冻结 SKILL.md`)
      assert.equal(
        fs.readFileSync(path.join(frozenRoot, 'SKILL.md'), 'utf8'),
        initialSkill,
        `${frozenRoot} 的冻结内容必须与保存内容一致`,
      )
    }

    const evidenceRoot = ensureEvidenceRoot()
    const statePath = path.join(evidenceRoot, 'full-demo-state.json')
    const existingState = fs.existsSync(statePath)
      ? JSON.parse(fs.readFileSync(statePath, 'utf8'))
      : {}
    fs.writeFileSync(
      statePath,
      `${JSON.stringify({ ...existingState, initialDraftId: draftId, initialCandidate: candidate }, null, 2)}\n`,
      'utf8',
    )
    await saveScreenshot(page, 'full-demo-initial-candidate.png')
    assertNoDiagnostics(diagnostics, 'Skill 初始 DRAFT 保存与 Candidate 冻结')

    console.log(`PASS: 初始 DRAFT ${draftId} 已保存并冻结为 ${candidate.version} (${candidate.id})。`)
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
