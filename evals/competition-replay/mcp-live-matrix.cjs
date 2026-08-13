const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const endpoint = process.env.MCP_URL || 'http://127.0.0.1:19091/mcp'
const evidenceRoot = path.resolve(__dirname, 'evidence')
const expectedTools = [
  'resolve_company',
  'get_company_profile',
  'get_company_financials',
  'get_company_intellectual_property',
  'get_company_risks',
  'get_company_relationships',
]
const evaluationCompanies = [
  '科大讯飞股份有限公司',
  '北京金山办公软件股份有限公司',
  '深信服科技股份有限公司',
  '浪潮电子信息产业股份有限公司',
  '用友网络科技股份有限公司',
]

let sequence = 0
let sessionId = null

function parseResponseBody(response, text) {
  if (!text) return null
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) return JSON.parse(text)
  if (contentType.includes('text/event-stream')) {
    // Streamable HTTP 允许同一个 POST /mcp 用 SSE 帧承载响应；这不是旧式 GET /sse + POST message 双端点。
    const dataLines = text.split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice('data:'.length).trim())
      .filter((line) => line && line !== '[DONE]')
    assert.equal(dataLines.length >= 1, true, 'Streamable HTTP SSE 响应必须包含 data 帧')
    return JSON.parse(dataLines[0])
  }
  throw new Error(`无法解析 MCP 响应类型 ${contentType || 'unknown'}`)
}

async function send(method, params, { notification = false, snapshotId } = {}) {
  const body = notification
    ? { jsonrpc: '2.0', method, params }
    : { jsonrpc: '2.0', id: ++sequence, method, params }
  const headers = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    'X-Evidence-Snapshot-Id': snapshotId,
  }
  if (sessionId) headers['Mcp-Session-Id'] = sessionId
  const response = await fetch(endpoint, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  })
  if (!sessionId && response.headers.get('mcp-session-id')) {
    sessionId = response.headers.get('mcp-session-id')
  }
  const text = await response.text()
  const json = parseResponseBody(response, text)
  return { response, json }
}

function parseToolResult(message) {
  assert.equal(message.json?.error, undefined, `JSON-RPC 调用失败：${JSON.stringify(message.json?.error)}`)
  const result = message.json?.result
  assert(result, 'tools/call 必须返回 result')
  const textBlock = result.content?.find((item) => item.type === 'text')
  assert(textBlock?.text, '工具结果必须包含 text block')
  if (result.isError) return { isError: true, text: textBlock.text }
  const value = JSON.parse(textBlock.text)
  return { isError: false, value }
}

async function callTool(name, argumentsValue, snapshotId) {
  const message = await send('tools/call', { name, arguments: argumentsValue }, { snapshotId })
  assert.equal(message.response.status, 200, `${name} HTTP 状态应为 200`)
  return parseToolResult(message)
}

function assertEnvelope(name, result, expectedEmpty = false) {
  assert.equal(result.isError, false, `${name} 合法调用不应返回工具错误`)
  assert.equal(typeof result.value.total, 'number')
  assert.equal(Array.isArray(result.value.items), true)
  assert.equal(Array.isArray(result.value.evidence), true)
  assert.equal(typeof result.value.truncated, 'boolean')
  if (expectedEmpty) {
    assert.equal(result.value.total, 0, `${name} 不存在主体用例应返回 0 条`)
    assert.equal(result.value.items.length, 0)
  }
}

async function main() {
  const snapshotId = crypto.randomUUID()
  const initialized = await send('initialize', {
    protocolVersion: '2025-03-26',
    capabilities: {},
    clientInfo: { name: 'competition-mcp-live-matrix', version: '1.0.0' },
  }, { snapshotId })
  assert.equal(initialized.response.status, 200)
  assert.equal(initialized.json.result.protocolVersion, '2025-03-26')
  assert(sessionId, 'Streamable HTTP initialize 必须返回 Mcp-Session-Id')

  const notification = await send('notifications/initialized', {}, { notification: true, snapshotId })
  assert.equal(notification.response.status, 202)

  const listed = await send('tools/list', {}, { snapshotId })
  assert.equal(listed.response.status, 200)
  const toolNames = listed.json.result.tools.map((tool) => tool.name).sort()
  assert.deepEqual(toolNames, [...expectedTools].sort())

  const exact = await callTool('resolve_company', { query: '科大讯飞股份有限公司' }, snapshotId)
  assertEnvelope('resolve_company', exact)
  const company = exact.value.items.find((item) => item.company_name === '科大讯飞股份有限公司')
    || exact.value.items[0]
  assert(company?.company_code, '主体搜索必须返回可供后续工具使用的 company_code')
  const companyId = String(company.company_code)

  const resolvedCompanyIds = {}
  for (const companyName of evaluationCompanies) {
    const result = await callTool('resolve_company', { query: companyName }, snapshotId)
    assertEnvelope('resolve_company', result)
    const matched = result.value.items.find((item) => item.company_name === companyName)
    assert(matched?.company_code, `${companyName} 必须能唯一映射到内部 companyId`)
    resolvedCompanyIds[companyName] = String(matched.company_code)
  }

  const datasetAudit = {}
  const toolTotalsByCompany = {}
  for (const companyName of evaluationCompanies) {
    datasetAudit[companyName] = {}
    toolTotalsByCompany[companyName] = {}
    for (const name of expectedTools.slice(1)) {
      const result = await callTool(name, { companyId: resolvedCompanyIds[companyName] }, snapshotId)
      assertEnvelope(name, result)
      toolTotalsByCompany[companyName][name] = result.value.total
      datasetAudit[companyName][name] = {
        subject: result.value.subject,
        total: result.value.total,
        items: result.value.items.slice(0, 2),
      }
    }
  }

  const successful = { resolve_company: exact.value.total }
  for (const name of expectedTools.slice(1)) {
    const result = await callTool(name, { companyId }, snapshotId)
    assertEnvelope(name, result)
    successful[name] = result.value.total
  }

  const missingSuffix = crypto.randomUUID().replaceAll('-', '')
  const emptyCases = {
    resolve_company: { query: `不存在企业-${missingSuffix}` },
  }
  for (const name of expectedTools.slice(1)) {
    emptyCases[name] = { companyId: `NON_EXISTENT_${missingSuffix}` }
  }
  for (const name of expectedTools) {
    const result = await callTool(name, emptyCases[name], snapshotId)
    assertEnvelope(name, result, true)
  }

  const invalidCases = { resolve_company: { query: 'x' } }
  for (const name of expectedTools.slice(1)) invalidCases[name] = { companyId: '' }
  for (const name of expectedTools) {
    const result = await callTool(name, invalidCases[name], snapshotId)
    assert.equal(result.isError, true, `${name} 非法参数必须失败关闭`)
    assert.match(result.text, /参数|invalid|MCP_ARGUMENT_INVALID/i)
  }

  const endpointRoot = new URL(endpoint)
  const legacyStatuses = {}
  for (const legacyPath of ['/sse', '/mcp/message']) {
    const response = await fetch(new URL(legacyPath, endpointRoot), { redirect: 'manual' })
    legacyStatuses[legacyPath] = response.status
    assert.equal(response.status, 404, `${legacyPath} 必须不存在`)
  }

  const summary = {
    testedAt: new Date().toISOString(),
    protocolVersion: initialized.json.result.protocolVersion,
    endpoint: '/mcp',
    toolNames: expectedTools,
    resolvedCompanyIds,
    toolTotalsByCompany,
    validCaseTotals: successful,
    emptyCasesPassed: expectedTools.length,
    invalidCasesPassed: expectedTools.length,
    legacyStatuses,
  }
  fs.mkdirSync(evidenceRoot, { recursive: true })
  fs.writeFileSync(path.join(evidenceRoot, 'mcp-live-matrix.json'), `${JSON.stringify(summary, null, 2)}\n`, 'utf8')
  if (process.env.PRINT_DATASET_AUDIT === '1') {
    console.log(JSON.stringify(datasetAudit, null, 2))
  }
  console.log(`PASS: 六工具成功/无结果/非法参数共 ${expectedTools.length * 3} 个用例通过，旧端点均为 404。`)
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
