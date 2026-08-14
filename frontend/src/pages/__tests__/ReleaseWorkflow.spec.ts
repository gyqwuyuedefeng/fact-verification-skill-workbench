import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'

import ReleasePage from '../ReleasePage.vue'
import { useEvaluationStore } from '../../stores/evaluation'
import { useReleaseStore } from '../../stores/release'
import { useSkillStore } from '../../stores/skill'
import type { EvaluationRun } from '../../types/evaluation'

const releaseState = {
  revision: 3,
  stableVersionId: '10000000-0000-0000-0000-000000000001',
  candidateVersionId: '20000000-0000-0000-0000-000000000002',
  previousStableVersionId: null,
  shadowEnabled: true,
  action: 'SHADOW_START' as const,
  reason: '开始影子验证',
  createdAt: '2026-08-12T00:00:00Z',
}

function evaluation(
  id: string,
  status: EvaluationRun['status'],
  gateStatus: EvaluationRun['gateStatus'],
  variantIds: string[],
  datasetVersion = 'public-tech-2024-v3',
  sampleCount = 30,
): EvaluationRun {
  return {
    id,
    datasetVersion,
    datasetHash: null,
    sampleCount,
    variants: variantIds.map((identifier) => ({ type: identifier === 'BASELINE' ? 'BASELINE' : 'SKILL', identifier, contentHash: 'a'.repeat(64) })),
    runManifest: null,
    metrics: null,
    status,
    gateStatus,
    gateReasons: null,
  }
}

describe('ReleaseWorkflow', () => {
  it('展示追加发布历史并触发影子启停、晋升和回滚', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const release = useReleaseStore()
    release.current = releaseState
    release.history = [{ ...releaseState, revision: 1, action: 'INITIALIZE' }, releaseState]
    const change = vi.spyOn(release, 'change').mockResolvedValue()

    const wrapper = mount(ReleasePage, { global: { plugins: [pinia] } })

    expect(wrapper.text()).toContain('候选版（CANDIDATE）不替换正式结果')
    expect(wrapper.text()).toContain('初始化（INITIALIZE）')
    expect(wrapper.text()).toContain('开启影子（SHADOW_START）')
    const button = wrapper.findAll('button').find((item) => item.text() === '晋升稳定版（STABLE）')
    await button?.trigger('click')
    expect(change).toHaveBeenCalledWith('promote', '同条件评测通过，进入真实材料影子验证')
  })

  it('独立管理页展示影子历史与差异，但明确不计算准确率', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const release = useReleaseStore()
    release.current = releaseState
    release.shadowHistory = {
      accuracyAvailable: false,
      summary: { total: 2, completed: 1, pass: 1, fail: 0, differentClaims: 3 },
      items: [{
        taskId: 'task-1',
        fileName: 'company.md',
        companyNames: '模拟科技股份有限公司',
        primaryRunId: '30000000-0000-0000-0000-000000000003',
        shadowRunId: '40000000-0000-0000-0000-000000000004',
        stableVersionId: releaseState.stableVersionId,
        candidateVersionId: releaseState.candidateVersionId,
        primaryStatus: 'COMPLETED',
        shadowStatus: 'COMPLETED',
        reviewStatus: 'PASS',
        agreementCount: 4,
        differenceCount: 1,
        createdAt: '2026-08-12T00:00:00Z',
      }],
    }
    const loadHistory = vi.spyOn(release, 'loadShadowHistory').mockResolvedValue()

    const wrapper = mount(ReleasePage, { global: { plugins: [pinia] } })

    expect(loadHistory).toHaveBeenCalled()
    expect(wrapper.text()).toContain('模拟科技股份有限公司')
    expect(wrapper.text()).toContain('主张差异 1')
    expect(wrapper.text()).toContain('影子真实材料没有金标，不计算准确率')
  })

  it('只允许为候选版选择已完成、门禁通过且包含当前 Stable 的精确评测，并展示中文状态', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const release = useReleaseStore()
    const skill = useSkillStore()
    const evaluations = useEvaluationStore()
    release.current = releaseState
    skill.versions = [
      {
        id: 'stable-1', skillKey: 'company-material-fact-check', version: 'v0.1.0', parentVersionId: null,
        status: 'STABLE', contentHash: 'a'.repeat(64), changeSummary: '稳定规则',
        createdAt: '2026-08-11T00:00:00Z', frozenAt: '2026-08-11T00:01:00Z',
      },
      {
        id: 'candidate-1', skillKey: 'company-material-fact-check', version: 'v0.2.0', parentVersionId: 'stable-1',
        status: 'CANDIDATE', contentHash: 'b'.repeat(64), changeSummary: '候选规则一',
        createdAt: '2026-08-12T00:00:00Z', frozenAt: '2026-08-12T00:01:00Z',
      },
      {
        id: 'candidate-2', skillKey: 'company-material-fact-check', version: 'v0.3.0', parentVersionId: 'stable-1',
        status: 'CANDIDATE', contentHash: 'c'.repeat(64), changeSummary: '候选规则二',
        createdAt: '2026-08-13T00:00:00Z', frozenAt: '2026-08-13T00:01:00Z',
      },
    ]
    evaluations.history = [
      evaluation('pass-related', 'COMPLETED', 'PASS', ['BASELINE', 'stable-1', 'candidate-1']),
      evaluation('missing-stable', 'COMPLETED', 'PASS', ['BASELINE', 'candidate-1']),
      evaluation('non-current-stable', 'COMPLETED', 'PASS', ['BASELINE', 'stable-old', 'candidate-1']),
      evaluation('extra-variant', 'COMPLETED', 'PASS', ['BASELINE', 'stable-1', 'extra', 'candidate-1']),
      evaluation('fail-related', 'COMPLETED', 'FAIL', ['BASELINE', 'stable-1', 'candidate-1']),
      evaluation('pass-unrelated', 'COMPLETED', 'PASS', ['BASELINE', 'stable-1', 'candidate-2']),
      evaluation('smoke-pass', 'COMPLETED', 'PASS', ['BASELINE', 'stable-1', 'candidate-1'], 'public-tech-live-smoke-v1', 3),
      evaluation('wrong-count-pass', 'COMPLETED', 'PASS', ['BASELINE', 'stable-1', 'candidate-1'], 'public-tech-2024-v3', 3),
    ]

    const wrapper = mount(ReleasePage, { global: { plugins: [pinia] } })
    const candidateSelect = wrapper.get('[data-test="release-candidate-version"]')

    expect(candidateSelect.text()).toContain('候选版（CANDIDATE）')
    expect(candidateSelect.text()).not.toContain('stable-1')
    expect(wrapper.text()).toContain('正式版（STABLE）')
    expect(wrapper.text()).toContain('候选版（CANDIDATE）')

    await candidateSelect.setValue('candidate-1')
    const evaluationSelect = wrapper.get('[data-test="release-evaluation-run"]')
    expect(evaluationSelect.text()).toContain('pass-related')
    expect(evaluationSelect.text()).toContain('门禁通过（PASS）')
    expect(evaluationSelect.text()).not.toContain('人工通过（PASS）')
    expect(evaluationSelect.text()).not.toContain('missing-stable')
    expect(evaluationSelect.text()).not.toContain('non-current-stable')
    expect(evaluationSelect.text()).not.toContain('extra-variant')
    expect(evaluationSelect.text()).not.toContain('fail-related')
    expect(evaluationSelect.text()).not.toContain('pass-unrelated')
    expect(evaluationSelect.text()).not.toContain('smoke-pass')
    expect(evaluationSelect.text()).not.toContain('wrong-count-pass')

    await evaluationSelect.setValue('pass-related')
    await candidateSelect.setValue('candidate-2')
    expect((evaluationSelect.element as HTMLSelectElement).value).toBe('')
  })
})
