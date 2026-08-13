const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { chromium } = require('playwright')

const replayRoot = path.resolve(__dirname, '..')
const evidenceRoot = path.join(replayRoot, 'evidence')
const baseUrl = process.env.PREVIEW_URL || 'http://127.0.0.1:15173'

function ensureEvidenceRoot() {
  fs.mkdirSync(evidenceRoot, { recursive: true })
  return evidenceRoot
}

async function openBrowser() {
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    locale: 'zh-CN',
  })
  const page = await context.newPage()
  return { browser, context, page }
}

function collectPageDiagnostics(page) {
  const errors = []
  page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))
  page.on('console', (message) => {
    if (message.type() === 'error') {
      errors.push(`console.error: ${message.text()}`)
    }
  })
  page.on('requestfailed', (request) => {
    const errorText = request.failure()?.errorText || ''
    const intentionallyClosedRunStream = request.method() === 'GET'
      && /\/api\/runs\/[^/]+\/events$/.test(request.url())
      && errorText === 'net::ERR_ABORTED'
    if (!intentionallyClosedRunStream) {
      errors.push(`requestfailed: ${request.method()} ${request.url()} ${errorText}`)
    }
  })
  page.on('response', (response) => {
    if (response.status() >= 400) {
      errors.push(`response: ${response.status()} ${response.request().method()} ${response.url()}`)
    }
  })
  return errors
}

async function gotoRoute(page, route) {
  const response = await page.goto(`${baseUrl}${route}`, { waitUntil: 'networkidle' })
  assert(response, `访问 ${route} 时没有收到 HTTP 响应`)
  assert.equal(response.status(), 200, `访问 ${route} 应返回 200`)
  await page.locator('#app').waitFor({ state: 'visible' })
}

async function saveScreenshot(page, fileName) {
  ensureEvidenceRoot()
  await page.screenshot({ path: path.join(evidenceRoot, fileName), fullPage: true })
}

function assertNoDiagnostics(errors, context) {
  assert.deepEqual(errors, [], `${context} 不应产生浏览器错误：\n${errors.join('\n')}`)
}

module.exports = {
  assert,
  baseUrl,
  collectPageDiagnostics,
  ensureEvidenceRoot,
  gotoRoute,
  openBrowser,
  replayRoot,
  saveScreenshot,
  assertNoDiagnostics,
}
