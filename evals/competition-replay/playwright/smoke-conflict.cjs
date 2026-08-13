const {
  assert,
  assertNoDiagnostics,
  collectPageDiagnostics,
  gotoRoute,
  openBrowser,
  saveScreenshot,
} = require('./helpers.cjs')
const cases = require('./test-cases.cjs')

async function main() {
  const { browser, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)
  try {
    await gotoRoute(page, '/verification')
    const diagnosticOffset = diagnostics.length
    await page.locator('[data-test="chat-message"]').fill(cases.financeConflict)
    await page.locator('[data-test="start-verification"]').click()
    const card = page.locator('.conversation-card').first()
    const status = card.locator('header .gate-chip')
    await page.waitForFunction(
      (element) => ['COMPLETED', 'FAILED', 'PARTIAL'].includes(element.textContent?.trim() || ''),
      await status.elementHandle(),
      { timeout: 300_000 },
    )
    assert.equal((await status.innerText()).trim(), 'COMPLETED', await card.innerText())
    assert.equal(await card.locator('.claim-card').count(), 1, '财务冲突输入应拆为一条主张')
    assert.equal(
      (await card.locator('.claim-status').innerText()).trim(),
      '存在冲突',
      `有同主体、同年份外部数据时，错误金额应判为冲突：${await card.innerText()}`,
    )
    assert.match(await card.locator('.evidence-stack').innerText(), /ads_lget_company_revenue/)
    assert.match(await card.locator('.live-timeline').innerText(), /get_company_financials/)
    await saveScreenshot(page, 'smoke-finance-conflict.png')
    assertNoDiagnostics(diagnostics.slice(diagnosticOffset), '财务冲突核验流程')
    process.stdout.write('PASS: 同主体同年份财务金额冲突被识别为 CONFLICT，并展示外部证据。\n')
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
