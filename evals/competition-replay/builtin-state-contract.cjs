#!/usr/bin/env node

// Task 8 的 fixture 与真实数据库验收共用同一派生器，避免 shell 中再次手写计数或关系期望。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const tableNames = [
  'claim',
  'verification_run',
  'verification_task',
  'evidence_snapshot',
  'release_binding',
  'skill_version',
  'evaluation_run',
]

function frequency(values) {
  return Object.fromEntries(
    [...new Set(values)].sort().map((value) => [value, values.filter((candidate) => candidate === value).length]),
  )
}

function canonicalUuid(value, context) {
  // 固定 fixture 使用便于讲解的合成 UUID，不要求 RFC 随机版本位，但仍锁定 Java UUID 可解析的 canonical 8-4-4-4-12 形态。
  assert.match(value, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/, `${context} 必须是 canonical UUID`)
  return value
}

function deriveExpectedState(document) {
  assert(document && typeof document === 'object' && document.tables, 'fixture 必须包含 tables')
  for (const table of tableNames) assert(Array.isArray(document.tables[table]), `${table} 必须是数组`)

  const allIds = []
  for (const table of tableNames) {
    for (const row of document.tables[table]) allIds.push(`${table}\t${canonicalUuid(row.id, `${table}.id`)}`)
  }
  assert.equal(new Set(allIds).size, allIds.length, '七表 UUID 不得重复')

  const tasks = new Map(document.tables.verification_task.map((row) => [row.id, row]))
  const taskRuns = new Map([...tasks.keys()].map((taskId) => [taskId, []]))
  for (const run of document.tables.verification_run) {
    canonicalUuid(run.task_id, 'verification_run.task_id')
    assert(taskRuns.has(run.task_id), `run ${run.id} 必须关联 fixture 内 task`)
    taskRuns.get(run.task_id).push(run.run_type)
  }
  const taskRunRelations = Object.fromEntries(
    [...taskRuns.entries()].sort().map(([taskId, runTypes]) => [taskId, [...runTypes].sort()]),
  )

  const releaseRows = [...document.tables.release_binding].sort((left, right) => left.revision - right.revision)
  assert.deepEqual(releaseRows.map((row) => row.revision), releaseRows.map((_, index) => index + 1), 'release revision 必须从 1 连续递增')

  return {
    tableCounts: Object.fromEntries(tableNames.map((table) => [table, document.tables[table].length])),
    skillStatuses: frequency(document.tables.skill_version.map((row) => row.status)),
    evaluationGates: frequency(document.tables.evaluation_run.map((row) => row.gate_status)),
    taskShadowRequested: Object.fromEntries(
      [...tasks.entries()].sort().map(([taskId, row]) => [taskId, row.shadow_requested]),
    ),
    taskRunRelations,
    shadowReviews: frequency(
      document.tables.verification_run
        .filter((row) => row.run_type === 'SHADOW')
        .map((row) => row.shadow_review_status),
    ),
    releaseActions: releaseRows.map((row) => row.action),
    allIds: allIds.sort(),
  }
}

function validateActualState(expected, actual) {
  for (const field of [
    'tableCounts',
    'skillStatuses',
    'evaluationGates',
    'taskShadowRequested',
    'taskRunRelations',
    'shadowReviews',
    'releaseActions',
    'allIds',
  ]) {
    assert.deepEqual(actual[field], expected[field], `${field} 必须与 builtin-demo.json 自动派生值一致`)
  }
}

function main(argv) {
  const fixturePath = path.resolve(__dirname, '../../backend/src/main/resources/demo-state/builtin-demo.json')
  const fixture = JSON.parse(fs.readFileSync(fixturePath, 'utf8'))
  const expected = deriveExpectedState(fixture)
  if (argv.length === 0 || argv[0] === '--self-test') {
    process.stdout.write(`${JSON.stringify(expected, null, 2)}\n`)
    return
  }
  assert.equal(argv[0], '--actual', '用法：builtin-state-contract.cjs [--self-test|--actual <数据库七表JSON>]')
  assert(argv[1], '--actual 必须提供数据库七表 JSON 路径')
  const actualDocument = JSON.parse(fs.readFileSync(argv[1], 'utf8'))
  const actual = deriveExpectedState(actualDocument)
  validateActualState(expected, actual)
  process.stdout.write(`BUILTIN_FIXTURE_DB_CONTRACT_OK ${JSON.stringify(actual)}\n`)
}

module.exports = { deriveExpectedState, validateActualState }

if (require.main === module) main(process.argv.slice(2))
