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

function scoringStabilityRules() {
  return `## 归一化与主体消歧

- \`normalizedClaim\` 只表达材料原文，不得用证据值重写。“存在至少一条”固定为 \`operator=EXISTS\`、\`value=true\`、\`unit=null\`；金额保留材料原数值和原单位。
- 风险否定主张固定为 \`metric=riskRecordAbsence\`、\`operator=EQUALS\`、\`value=true\`；空结果仍是 \`INSUFFICIENT\`。
- 全称查询返回多条同 \`company_name\` 且同 \`uni_code\` 的记录时，它们是主索引重复实体，不是业务上的同名公司。最多对前两个不同 \`company_code\` 调用本主张对应证据工具，选择 \`total\` 较大者；并列时保留搜索顺序第一条。
- 简称只在返回候选中恰好一条 \`company_sname\` 与材料简称完全相同时可唯一定位；不得因公司全称仅包含该短词就选第一条。
- 工商主张不需要为选主体扫描其他索引；完整企业全称的重复记录并列时取第一条。
`
}

function emptyResultDecisionRule() {
  return `## 空结果判定补强

- 对“存在至少一条”的正向存在性主张，对应证据工具返回 \`total=0\` 只表示当前索引未返回直接支持，不构成反证；必须输出 \`INSUFFICIENT\`、\`evidence=[]\` 并请求人工介入，不能输出 \`CONFLICT\`。
`
}

function optimizeSkill(content) {
  if (content.includes('## 归一化与主体消歧')) {
    if (content.includes('## 空结果判定补强')) {
      return content
    }
    return `${content.trim()}\n\n${emptyResultDecisionRule()}`
  }
  if (content.includes('## 单主张快速路径')) {
    return `${content.trim()}\n\n${scoringStabilityRules()}\n## 最终输出前自检\n\n- 每条输入只保留一个对应主张，\`claimText\`、locator 和归一化五元组不得改写。\n- \`subject.companyId\` 必须来自主体工具，不能误填股票代码或统一社会信用代码。\n- \`VERIFIED\`/\`CONFLICT\` 必须有与 items 同 recordId 的 evidence；否则降级为 \`INSUFFICIENT\`。\n- 输出前检查顶层运行元数据逐字复制、JSON 无代码围栏且只含 schema 字段。\n`
  }
  const optimized = content
    .replace(
      '1. 读取 `references/claim-normalization.md`，从文档快照提取可核验主张并原样保留 `materialLocator`。',
      '1. 直接从文档快照提取一条可核验主张，逐字保留输入已有的 `materialLocator`；不要为单条材料补写其他主张。',
    )
    .replace(
      '4. 读取 `references/evidence-rules.md`，对齐主体、指标、期间、口径、数值和单位后再判定。',
      '4. 直接按主体、指标、期间、口径、数值、单位的顺序比较；不要额外读取 reference 文件，减少模型往返。',
    )

  return `${optimized.trim()}\n\n## 单主张快速路径\n\n- “截至本次证据检索”的工商和存在性事实使用 \`CURRENT\`；明确年份必须逐字保留。\n- 先从 \`resolve_company\` 取唯一 \`company_code\`，再把它作为后续工具的 \`companyId\`；简称歧义不得猜主体。\n- 财务值先统一元、万元、亿元再比较；证据不一致输出 \`CONFLICT\`。\n- \`VERIFIED\`/\`CONFLICT\` 的 evidence 引用与 items 必须用相同 \`recordId\` 配对，并原样复制对应 item 为 content。\n- 否定性风险、缺期间、空结果或工具失败一律 \`INSUFFICIENT\` 且请求人工介入。\n\n${scoringStabilityRules()}`
}

async function main() {
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'))
  if (state.formalInitialEvaluation?.gateStatus === 'FAIL' && state.formalInitialEvaluationId) {
    const rejectedId = state.formalInitialEvaluationId
    const rejectedRoot = path.join(reportsRoot, 'rejected', `${rejectedId.slice(0, 8)}-candidate-regression`)
    fs.mkdirSync(rejectedRoot, { recursive: true })
    for (const name of [
      'initial-evaluation-report.md',
      'initial-evaluation-report.json',
      'initial-run-manifest.json',
      'initial-failed-samples.json',
      'initial-version-card.json',
    ]) {
      const source = path.join(reportsRoot, name)
      if (fs.existsSync(source)) fs.copyFileSync(source, path.join(rejectedRoot, name))
    }
    state.rejectedCandidateRegressionEvaluationId = rejectedId
    state.rejectedCandidateRegressionReason = '候选 Skill 把存在性主张改写为数量主张，并直接选用主索引重复实体的第一个 company_code，导致归一化与下游证据不匹配；保留为评测驱动优化证据。'
    delete state.formalInitialEvaluationId
    delete state.formalInitialEvaluation
    fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`)
  }
  const baseVersionId = state.initialStable?.versionId ?? state.bootstrapCandidate?.id ?? state.initialCandidate?.id
  assert(baseVersionId, '缺少可克隆的初始冻结版本')

  const { browser, context, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)
  try {
    await gotoRoute(page, '/admin/skills')
    const versionsResponse = await context.request.get(`${baseUrl}/api/skills/company-material-fact-check/versions`)
    assert.equal(versionsResponse.status(), 200)
    const versions = await versionsResponse.json()
    const baseVersion = versions.find((item) => item.id === baseVersionId)
    assert(baseVersion, '版本清单中找不到初始 Stable')

    await page.locator('button.version-row').filter({ hasText: baseVersion.version }).click()
    const createResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
      && /\/api\/skills\/company-material-fact-check\/drafts$/.test(response.url()))
    await page.locator('[data-test="create-draft"]').click()
    const createResponse = await createResponsePromise
    assert.equal(createResponse.status(), 201)
    const draft = await createResponse.json()
    assert.equal(draft.parentVersionId, baseVersionId)

    const editor = page.locator('textarea.code-editor').first()
    await page.waitForFunction(() => Boolean(document.querySelector('textarea.code-editor')?.value))
    const baseContent = await editor.inputValue()
    const optimizedContent = optimizeSkill(baseContent)
    assert.notEqual(optimizedContent, baseContent)
    assert.equal(optimizedContent.includes('读取 `references/'), false, '优化版不得继续要求额外读取 reference')
    assert.equal(optimizedContent.includes('## 归一化与主体消歧'), true)
    assert.equal(optimizedContent.includes('operator=EXISTS'), true)
    assert.equal(optimizedContent.includes('company_sname'), true)
    await editor.fill(optimizedContent)
    assert.equal(optimizedContent.includes('## 空结果判定补强'), true)
    await page.locator('input.text-input').first().fill('修复正向存在性主张空结果误判为冲突')

    const saveResponsePromise = page.waitForResponse((response) => response.request().method() === 'PUT'
      && response.url().endsWith(`/api/skills/company-material-fact-check/drafts/${draft.id}`))
    await page.getByRole('button', { name: '保存 DRAFT' }).click()
    const saveResponse = await saveResponsePromise
    assert.equal(saveResponse.status(), 200)

    const freezeResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
      && response.url().endsWith(`/api/skills/company-material-fact-check/drafts/${draft.id}/freeze`))
    await page.locator('[data-test="freeze-draft"]').click()
    const freezeResponse = await freezeResponsePromise
    assert.equal(freezeResponse.status(), 201)
    const candidate = await freezeResponse.json()
    assert.equal(candidate.status, 'CANDIDATE')
    assert.equal(candidate.parentVersionId, baseVersionId)
    assert.match(candidate.contentHash, /^[0-9a-f]{64}$/)

    await page.locator('[data-test="target-version"]').selectOption(candidate.id)
    await page.locator('[data-test="base-version"]').selectOption(baseVersionId)
    const comparisonResponsePromise = page.waitForResponse(
      (response) => response.request().method() === 'POST'
        && response.url().endsWith(`/api/skills/company-material-fact-check/versions/${candidate.id}/comparison`),
      { timeout: 300_000 },
    )
    await page.locator('[data-test="generate-change-summary"]').click()
    const comparisonResponse = await comparisonResponsePromise
    assert.equal(comparisonResponse.status(), 200)
    const comparison = await comparisonResponse.json()
    assert.match(comparison.deterministicDiff, /空结果判定补强/)
    assert.equal(comparison.summaryStatus, 'COMPLETED', '公司模型必须成功生成仅供审核参考的升级说明')
    assert(comparison.generatedSummary?.headline)
    await page.locator('[data-test="deterministic-diff"]').waitFor({ state: 'visible' })
    await page.getByText('模型生成、仅供审核参考', { exact: true }).waitFor({ state: 'visible' })

    const cardResponse = await context.request.get(
      `${baseUrl}/api/skills/company-material-fact-check/versions/${candidate.id}/card`,
    )
    assert.equal(cardResponse.status(), 200)
    const versionCard = await cardResponse.json()
    fs.mkdirSync(reportsRoot, { recursive: true })
    fs.writeFileSync(path.join(reportsRoot, 'candidate-version-card.json'), `${JSON.stringify(versionCard, null, 2)}\n`)
    fs.writeFileSync(path.join(reportsRoot, 'skill-version-comparison.json'), `${JSON.stringify(comparison, null, 2)}\n`)

    if (state.initialStable) {
      state.optimizedCandidate = candidate
    } else {
      state.bootstrapCandidate = candidate
    }
    state.skillVersionComparison = comparison
    fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`)
    await saveScreenshot(page, 'full-demo-optimized-candidate-and-ai-summary.png')
    assertNoDiagnostics(diagnostics, '克隆、保存、冻结优化 Candidate 并生成升级说明')
    console.log(`PASS: 优化 Candidate ${candidate.version} 已冻结，确定性差异与公司模型升级说明均可查看。`)
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
