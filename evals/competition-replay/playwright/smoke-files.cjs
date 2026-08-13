const fs = require('node:fs')
const path = require('node:path')

const {
  assert,
  assertNoDiagnostics,
  collectPageDiagnostics,
  ensureEvidenceRoot,
  gotoRoute,
  openBrowser,
  saveScreenshot,
} = require('./helpers.cjs')
const cases = require('./test-cases.cjs')

async function waitForTerminal(card) {
  const statusChip = card.locator('header .gate-chip')
  await statusChip.waitFor()
  await card.page().waitForFunction(
    (element) => ['COMPLETED', 'FAILED', 'PARTIAL'].includes(element.textContent?.trim() || ''),
    await statusChip.elementHandle(),
    { timeout: 300_000 },
  )
  return (await statusChip.innerText()).trim()
}

async function runAttachmentCase(page, diagnostics, testCase, index) {
  const diagnosticOffset = diagnostics.length
  const input = page.locator('input[type="file"]')
  await input.setInputFiles(testCase.file)
  const fileName = path.basename(testCase.file)
  const attachment = page.locator('.attachment-chip')
  await attachment.getByText(fileName, { exact: true }).waitFor()

  if (index === 0) {
    await attachment.getByRole('button', { name: '移除附件' }).click()
    assert.equal(await attachment.count(), 0, '移除附件按钮必须清空当前附件')
    assert.equal(
      await page.locator('[data-test="start-verification"]').isDisabled(),
      true,
      '没有文字和附件时发送按钮必须重新禁用',
    )
    await input.setInputFiles(testCase.file)
    await page.locator('.attachment-chip').getByText(fileName, { exact: true }).waitFor()
  }

  await page.locator('[data-test="chat-message"]').fill(testCase.prompt)
  await page.locator('[data-test="start-verification"]').click()
  const card = page.locator('.conversation-card').first()
  await card.locator('header').getByText(fileName, { exact: true }).waitFor()
  const terminalStatus = await waitForTerminal(card)
  if (terminalStatus !== 'COMPLETED') {
    await saveScreenshot(page, `smoke-file-${index + 1}-failed.png`)
  }
  assert.equal(
    terminalStatus,
    'COMPLETED',
    `${fileName} 核验应完成，实际为 ${terminalStatus}：${await card.innerText()}`,
  )
  assert.match(await card.locator('.snapshot-grid').innerText(), /COMBINED/)
  assert.match(await card.locator('.snapshot-grid .wide code').innerText(), /^[0-9a-f]{64}$/)
  assert.match(await card.locator('.live-timeline').innerText(), /TOOL_STARTED/)
  assert.match(await card.locator('.live-timeline').innerText(), /RUN_COMPLETED/)

  const claims = card.locator('.claim-card')
  const claimCount = await claims.count()
  assert(claimCount >= 1, `${fileName} 至少应拆出一条可核验主张`)
  const statuses = await card.locator('.claim-status').allInnerTexts()
  assert(
    statuses.every((status) => status.trim() === '证据不足'),
    `${fileName} 是虚构主体，不得把附件自证为真；实际状态：${statuses.join(', ')}`,
  )
  const timelineEvents = await card.locator('.live-timeline code').count()
  assert(timelineEvents < 50, `${fileName} 的执行轨迹应折叠文本增量，实际显示 ${timelineEvents} 条`)
  await saveScreenshot(page, `smoke-file-${index + 1}-${path.extname(testCase.file).slice(1)}.png`)
  assertNoDiagnostics(diagnostics.slice(diagnosticOffset), `${fileName} 附件核验流程`)
  return { fileName, terminalStatus, claimCount, statuses, timelineEvents }
}

async function main() {
  const { browser, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)
  const results = []
  try {
    await gotoRoute(page, '/verification')
    const attachmentCases = [
      cases.markdown,
      cases.textAmbiguity,
      cases.csv,
      cases.word,
      cases.powerpoint,
      cases.excel,
      cases.pdf,
    ]
    for (const [index, testCase] of attachmentCases.entries()) {
      results.push(await runAttachmentCase(page, diagnostics, testCase, index))
    }
    fs.writeFileSync(
      path.join(ensureEvidenceRoot(), 'smoke-files.json'),
      `${JSON.stringify({ generatedAt: new Date().toISOString(), results }, null, 2)}\n`,
      'utf8',
    )
    process.stdout.write('PASS: Markdown、TXT、CSV 上传、移除附件、解析、Agent 核验与结果卡均正常。\n')
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
