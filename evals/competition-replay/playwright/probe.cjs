const fs = require('node:fs')
const path = require('node:path')
const {
  collectPageDiagnostics,
  ensureEvidenceRoot,
  gotoRoute,
  openBrowser,
  saveScreenshot,
} = require('./helpers.cjs')

const routes = [
  ['verification', '/verification'],
  ['evaluation', '/admin/evaluations'],
  ['skills', '/admin/skills'],
  ['release', '/admin/releases'],
]

async function main() {
  const { browser, page } = await openBrowser()
  const diagnostics = collectPageDiagnostics(page)
  const report = {}
  try {
    for (const [name, route] of routes) {
      await gotoRoute(page, route)
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
