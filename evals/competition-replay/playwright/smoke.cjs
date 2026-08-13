const {
  assert,
  assertNoDiagnostics,
  collectPageDiagnostics,
  gotoRoute,
  openBrowser,
  saveScreenshot,
} = require('./helpers.cjs')
const cases = require('./test-cases.cjs')

async function assertNavigation(page) {
  const routes = [
    ['对照评测', '/admin/evaluations', '管理评测'],
    ['Skill 版本', '/admin/skills', 'Skill 版本实验室'],
    ['影子与发布', '/admin/releases', '影子与发布'],
    ['事实核验对话', '/verification', '企业材料事实核验'],
  ]
  for (const [linkText, route, heading] of routes) {
    await page.getByRole('link', { name: linkText, exact: true }).click()
    await page.waitForURL(`**${route}`)
    await page.getByRole('heading', { name: heading, exact: true }).waitFor()
  }
}

async function runTextVerification(page) {
  const sendButton = page.locator('[data-test="start-verification"]')
  assert.equal(await sendButton.isDisabled(), true, '空输入时发送按钮必须禁用')

  const stableMode = page.getByRole('button', { name: /当前 Stable/ })
  const baselineMode = page.getByRole('button', { name: /通用基线/ })
  await stableMode.click()
  assert.match(await page.locator('.stable-chip').innerText(), /当前 Stable/)
  await baselineMode.click()
  assert.match(await page.locator('.stable-chip').innerText(), /通用基线/)

  const composer = page.locator('[data-test="chat-message"]')
  await composer.fill('换行行为')
  await composer.press('Shift+Enter')
  assert.match(await composer.inputValue(), /换行行为\n/, 'Shift+Enter 必须换行且不能发送')
  assert.equal(await page.locator('.conversation-card').count(), 0)
  await composer.fill(cases.textFacts)
  assert.equal(await sendButton.isEnabled(), true, '有文本时发送按钮必须启用')
  await composer.press('Enter')

  const card = page.locator('.conversation-card').first()
  await card.waitFor({ state: 'visible' })
  await card.getByText('01', { exact: true }).waitFor()
  await card.getByText('02', { exact: true }).waitFor()
  await card.getByText('03', { exact: true }).waitFor()
  const statusChip = card.locator('header .gate-chip')
  await statusChip.waitFor()
  await page.waitForFunction(
    (element) => ['COMPLETED', 'FAILED', 'PARTIAL'].includes(element.textContent?.trim() || ''),
    await statusChip.elementHandle(),
    { timeout: 300_000 },
  )
  const terminalStatus = (await statusChip.innerText()).trim()
  if (terminalStatus !== 'COMPLETED') {
    await saveScreenshot(page, 'smoke-text-verification-failed.png')
  }
  assert.equal(terminalStatus, 'COMPLETED', `真实文本核验应完成，页面任务卡实际为 ${terminalStatus}：${await card.innerText()}`)

  const claimCount = await card.locator('.claim-card').count()
  assert(claimCount >= 1, `真实文本核验至少应返回一条主张，实际为 ${claimCount}`)
  assert.match(await card.locator('.live-timeline').innerText(), /RUN_CREATED/)
  assert.match(await card.locator('.live-timeline').innerText(), /RUN_COMPLETED/)
  await saveScreenshot(page, 'smoke-text-verification.png')
}

async function main() {
  const { browser, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)
  try {
    await gotoRoute(page, '/verification')
    await assertNavigation(page)
    const diagnosticOffset = diagnostics.length
    await runTextVerification(page)
    assertNoDiagnostics(diagnostics.slice(diagnosticOffset), '文本核验流程')
    process.stdout.write('PASS: 页面导航、运行方式、文本核验、01/02/03 实时任务卡均正常。\n')
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
