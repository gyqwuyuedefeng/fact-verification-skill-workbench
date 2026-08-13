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
const { demoStateCases } = require('./test-cases.cjs')

const routes = [
  ['verification', '/verification'],
  ['evaluation', '/admin/evaluations'],
  ['skills', '/admin/skills'],
  ['release', '/admin/releases'],
  ['demoState', demoStateCases.route],
]

const metricLabels = [
  '准确率（accuracy）',
  '任务完成率（completionRate）',
  '稳定性（stability）',
  '人工介入率（humanInterventionRate）',
]

async function assertSelect(page, selector, label) {
  const locator = page.locator(selector)
  await locator.waitFor({ state: 'visible' })
  assert.equal(await locator.evaluate((node) => node.tagName), 'SELECT', `${label}必须使用 select`)
}

async function assertEvaluationPage(page) {
  const history = page.locator('.history-row')
  assert((await history.count()) > 0, '非破坏性探针需要至少一个历史评测以检查四项指标中文名')
  await history.first().click()
  // 历史按钮的 Vue click handler 在当前文档内异步拉取评测，不会触发新的页面 load；等待首个指标比 networkidle 更精确。
  await page.getByText(metricLabels[0], { exact: true }).waitFor({ state: 'visible' })
  const body = await page.locator('body').innerText()
  for (const label of metricLabels) {
    assert(body.includes(label), `管理评测页缺少中文指标：${label}`)
  }
  await assertSelect(page, '[data-test="stable-version"]', '稳定版版本')
  await assertSelect(page, '[data-test="candidate-version"]', '候选版版本')
}

async function assertReleasePage(page) {
  await assertSelect(page, '[data-test="release-candidate-version"]', '发布候选版本')
  await assertSelect(page, '[data-test="release-evaluation-run"]', '发布关联评测')
}

async function assertDemoStatePage(page) {
  await page.getByRole('link', { name: '演示数据', exact: true }).waitFor({ state: 'visible' })
  const status = page.locator('[data-test="demo-state-status"]')
  await status.waitFor({ state: 'visible' })
  assert((await status.innerText()).includes('当前演示数据状态'), '演示数据页缺少当前状态区域')
  const body = await page.locator('body').innerText()
  assert(body.includes(demoStateCases.builtinNotice), '演示数据页缺少固定数据非现场生成提示')
  assert(body.includes(demoStateCases.resetPhrase), '演示数据页缺少清空确认短语提示')
}

async function main() {
  const { browser, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)
  const report = {}
  try {
    for (const [name, route] of routes) {
      await gotoRoute(page, route)
      if (name === 'evaluation') await assertEvaluationPage(page)
      if (name === 'release') await assertReleasePage(page)
      if (name === 'demoState') await assertDemoStatePage(page)
      report[name] = {
        title: await page.title(),
        headings: await page.getByRole('heading').allTextContents(),
        links: await page.getByRole('link').allTextContents(),
        buttons: await page.getByRole('button').allTextContents(),
        inputs: await page.locator('input, textarea, select').evaluateAll((nodes) =>
          nodes.map((node) => ({
            tag: node.tagName,
            type: node.getAttribute('type'),
            placeholder: node.getAttribute('placeholder'),
            value: node.value,
          })),
        ),
        body: (await page.locator('body').innerText()).slice(0, 12000),
      }
      await saveScreenshot(page, `probe-${name}.png`)
    }
    report.diagnostics = diagnostics
    assertNoDiagnostics(diagnostics, '非破坏性页面探针')
    ensureEvidenceRoot()
    fs.writeFileSync(
      path.join(ensureEvidenceRoot(), 'probe-pages.json'),
      `${JSON.stringify(report, null, 2)}\n`,
      'utf8',
    )
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`)
  } finally {
    await browser.close()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
