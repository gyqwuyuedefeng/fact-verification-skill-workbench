const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')

const { deriveExpectedState, validateActualState } = require('./builtin-state-contract.cjs')

const fixturePath = path.resolve(
  __dirname,
  '../../backend/src/main/resources/demo-state/builtin-demo.json',
)

test('从内置 fixture 自动派生七表计数和完整关系合同', () => {
  const expected = deriveExpectedState(JSON.parse(fs.readFileSync(fixturePath, 'utf8')))

  assert.deepEqual(expected.tableCounts, {
    claim: 4,
    verification_run: 4,
    verification_task: 2,
    evidence_snapshot: 4,
    release_binding: 5,
    skill_version: 4,
    evaluation_run: 3,
  })
  assert.deepEqual(expected.skillStatuses, { ARCHIVED: 1, CANDIDATE: 1, DRAFT: 1, STABLE: 1 })
  assert.deepEqual(expected.evaluationGates, { FAIL: 1, PASS: 2 })
  assert.deepEqual(expected.shadowReviews, { FAIL: 1, PASS: 1 })
  assert.deepEqual(expected.releaseActions, [
    'INITIALIZE',
    'REGISTER',
    'SHADOW_START',
    'PROMOTE',
    'ROLLBACK',
  ])
  assert.equal(expected.allIds.length, 26)
})

test('数据库只读投影必须逐项等于 fixture 自动派生合同', () => {
  const fixture = JSON.parse(fs.readFileSync(fixturePath, 'utf8'))
  const expected = deriveExpectedState(fixture)
  const actual = JSON.parse(JSON.stringify(expected))

  assert.doesNotThrow(() => validateActualState(expected, actual))
  actual.tableCounts.claim += 1
  assert.throws(() => validateActualState(expected, actual), /tableCounts/)
})
