import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'

import ReleasePage from '../ReleasePage.vue'
import { useReleaseStore } from '../../stores/release'

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

describe('ReleaseWorkflow', () => {
  it('展示追加发布历史并触发影子启停、晋升和回滚', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const release = useReleaseStore()
    release.current = releaseState
    release.history = [releaseState]
    const change = vi.spyOn(release, 'change').mockResolvedValue()

    const wrapper = mount(ReleasePage, { global: { plugins: [pinia] } })

    expect(wrapper.text()).toContain('Candidate 不替换正式结果')
    expect(wrapper.text()).toContain('SHADOW_START')
    const button = wrapper.findAll('button').find((item) => item.text() === '晋升 Stable')
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
})
