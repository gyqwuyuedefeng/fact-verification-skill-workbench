const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const assert = require('node:assert/strict')

const scriptPath = path.join(
  __dirname,
  'playwright',
  'release-shadow-promote-rollback.cjs',
)

/**
 * 完整演示必须在证明“可回滚到旧版”后，再恢复评测最优版为最终 Stable。
 * 这个静态合同防止复演脚本停留在旧版，导致自动化结果与已导出的真实演示快照不一致。
 */
test('完整发布复演最终恢复优化版并兼容当前六步历史', () => {
  const source = fs.readFileSync(scriptPath, 'utf8')

  assert.match(source, /currentBefore\.stableVersionId === candidateId/)
  assert.match(source, /现场演示完成：恢复评测最优版本为正式稳定版/)
  assert.match(source, /getByText\(\/回滚上一版（ROLLBACK）\/\)/)
  assert.match(source, /\['ROLLBACK', 'ROLLBACK', 'PROMOTE', 'SHADOW_START', 'REGISTER', 'INITIALIZE'\]/)
  assert.match(source, /\['ROLLBACK', 'ROLLBACK', 'PROMOTE', 'SHADOW_START', 'SHADOW_STOP', 'SHADOW_START', 'REGISTER', 'INITIALIZE'\]/)
})
