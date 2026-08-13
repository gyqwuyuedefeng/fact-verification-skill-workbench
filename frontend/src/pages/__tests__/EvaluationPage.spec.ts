import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import EvaluationPage from '../EvaluationPage.vue'
import { useEvaluationStore } from '../../stores/evaluation'
import { useSkillStore } from '../../stores/skill'
import type { EvaluationRun } from '../../types/evaluation'
import type { SkillVersion } from '../../types/skill'

function version(id: string, status: SkillVersion['status']): SkillVersion {
  return {
    id,
    skillKey: 'fact-verification',
    version: `v-${id}`,
    parentVersionId: null,
    status,
    contentHash: `${id}-hash`,
    changeSummary: `${id} 变更`,
    createdAt: '2026-08-13T00:00:00Z',
    frozenAt: status === 'DRAFT' ? null : '2026-08-13T00:00:00Z',
  }
}

function run(id: string, status: EvaluationRun['status']): EvaluationRun {
  return {
    id,
    datasetVersion: 'public-tech-2024-v3',
    datasetHash: null,
    sampleCount: 30,
    variants: null,
    runManifest: null,
    metrics: null,
    status,
    gateStatus: 'PENDING',
    gateReasons: null,
  }
}

function evaluationRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin/evaluations', component: EvaluationPage }],
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function response(body: unknown): Response {
  return {
    ok: true,
    json: async () => body,
  } as Response
}

describe('EvaluationPage', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('展示锁定条件、四项核心指标和报告导出', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useEvaluationStore()
    store.evaluation = {
      id: 'evaluation-1',
      datasetVersion: 'public-tech-2024-v1',
      datasetHash: 'a'.repeat(64),
      sampleCount: 30,
      variants: [
        { type: 'BASELINE', identifier: 'BASELINE', contentHash: 'b'.repeat(64) },
        { type: 'SKILL', identifier: 'stable-v1', contentHash: 'c'.repeat(64) },
        { type: 'SKILL', identifier: 'candidate-v2', contentHash: 'd'.repeat(64) },
      ],
      runManifest: {
        modelConfigHash: 'e'.repeat(64),
        modelParameters: {
          temperature: 0,
          topP: 1,
          seed: 20260812,
          parallelToolCalls: false,
          maxTokens: 8192,
          enableThinking: false,
        },
        toolContractHash: 'f'.repeat(64),
        evidenceSnapshotHash: '0'.repeat(64),
        outputSchemaHash: '1'.repeat(64),
      },
      metrics: {
        BASELINE: {
          accuracy: { definition: '准确', numerator: 20, denominator: 30, value: 0.6667 },
          completionRate: { definition: '完成', numerator: 28, denominator: 30, value: 0.9333 },
          stability: { definition: '稳定', numerator: 8, denominator: 10, value: 0.8 },
          humanInterventionRate: {
            definition: '介入',
            numerator: 10,
            denominator: 30,
            value: 0.3333,
          },
        },
      },
      status: 'COMPLETED',
      gateStatus: 'PASS',
      gateReasons: [{ name: 'conditions-locked', passed: true, reason: '同条件已锁定' }],
    }
    store.samples = [{
      sampleId: 'iflytek-basic',
      gold: {
        expectedStatus: 'VERIFIED',
        material: { text: '科大讯飞统一社会信用代码正确。' },
      },
      variantResults: {
        BASELINE: {
          score: {
            sampleId: 'iflytek-basic',
            accurate: false,
            completed: true,
            requiresHumanIntervention: true,
          },
          attempts: [{
            attempt: 1,
            output: { claims: [{ status: 'CONFLICT' }] },
            durationMs: 1234,
            errorCode: null,
          }],
        },
      },
    }]

    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia] } })

    expect(wrapper.text()).toContain('同条件锁定')
    expect(wrapper.text()).toContain('temperature=0')
    expect(wrapper.text()).toContain('seed=20260812')
    expect(wrapper.text()).toContain('maxTokens=8192')
    expect(wrapper.text()).toContain('enableThinking=false')
    expect(wrapper.text()).toContain('准确率')
    expect(wrapper.text()).toContain('任务完成率')
    expect(wrapper.text()).toContain('稳定性')
    expect(wrapper.text()).toContain('人工介入率')
    expect(wrapper.text()).toContain('iflytek-basic')
    expect(wrapper.text()).toContain('金标 已核验（VERIFIED）')
    expect(wrapper.text()).toContain('基线（BASELINE）')
    expect(wrapper.text()).toContain('存在冲突（CONFLICT）')
    expect(wrapper.text()).toContain('GATE 通过（PASS）')
    expect(wrapper.text()).toContain('评分失败')
    expect(wrapper.text()).toContain('导出报告')
  })

  it('以基线、稳定版和候选版创建评测，并显示中文标签', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = evaluationRouter()
    await router.push('/admin/evaluations')
    await router.isReady()
    const store = useEvaluationStore()
    const skillStore = useSkillStore()
    skillStore.versions = [version('stable-v1', 'STABLE'), version('candidate-v2', 'CANDIDATE')]
    vi.spyOn(skillStore, 'load').mockResolvedValue()
    const start = vi.spyOn(store, 'start').mockResolvedValue(run('evaluation-created', 'PENDING'))
    vi.spyOn(store, 'refreshEvaluation').mockResolvedValue()
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia, router] } })

    expect(wrapper.text()).toContain('稳定版（STABLE）')
    expect(wrapper.text()).toContain('候选版（CANDIDATE）')
    expect(wrapper.text()).toContain('基线（BASELINE）')

    await wrapper.find('[data-test="stable-version"]').setValue('stable-v1')
    await wrapper.find('[data-test="candidate-version"]').setValue('candidate-v2')
    await wrapper.find('[data-test="start-evaluation"]').trigger('click')
    await flushPromises()

    expect(start).toHaveBeenCalledWith('public-tech-2024-v3', [
      'BASELINE',
      'stable-v1',
      'candidate-v2',
    ])
    expect(router.currentRoute.value.query.evaluationId).toBe('evaluation-created')
  })

  it('没有 Stable 时只以 BASELINE 和 Candidate 创建评测', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useEvaluationStore()
    const skillStore = useSkillStore()
    skillStore.versions = [version('candidate-v1', 'CANDIDATE')]
    vi.spyOn(skillStore, 'load').mockResolvedValue()
    const start = vi.spyOn(store, 'start').mockResolvedValue()
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia] } })

    await wrapper.find('[data-test="candidate-version"]').setValue('candidate-v1')
    await wrapper.find('[data-test="start-evaluation"]').trigger('click')

    expect(start).toHaveBeenCalledWith('public-tech-2024-v3', ['BASELINE', 'candidate-v1'])
  })

  it('管理页提供评测批次、按版本汇总和版本对比三个历史视图', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useEvaluationStore()
    const loadHistory = vi.spyOn(store, 'loadHistory').mockResolvedValue()
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia] } })
    await flushPromises()

    expect(loadHistory).toHaveBeenCalled()
    expect(wrapper.find('[data-test="evaluation-tab-runs"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="evaluation-tab-version"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="evaluation-tab-compare"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('历史评测')
  })

  it('辅助数据仍在加载时立即按 URL 刷新评测', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = evaluationRouter()
    await router.push('/admin/evaluations?evaluationId=evaluation-from-url')
    await router.isReady()
    const store = useEvaluationStore()
    const skillStore = useSkillStore()
    const versions = deferred<void>()
    const history = deferred<void>()
    vi.spyOn(skillStore, 'load').mockReturnValue(versions.promise)
    vi.spyOn(store, 'loadHistory').mockReturnValue(history.promise)
    const refresh = vi.spyOn(store, 'refreshEvaluation').mockResolvedValue()
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia, router] } })
    await Promise.resolve()

    expect(refresh).toHaveBeenCalledWith('evaluation-from-url')

    versions.resolve()
    history.resolve()
    wrapper.unmount()
  })

  it('刷新状态不会让创建按钮误显示创建中', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useEvaluationStore()
    const skillStore = useSkillStore()
    skillStore.versions = [version('candidate-v1', 'CANDIDATE')]
    vi.spyOn(skillStore, 'load').mockResolvedValue()
    store.busy = true
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia] } })

    await wrapper.get('[data-test="candidate-version"]').setValue('candidate-v1')

    expect(wrapper.get('[data-test="start-evaluation"]').text()).not.toContain('正在创建评测')
  })

  it('Stable 和 Candidate 均使用按状态过滤的下拉选项', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const skillStore = useSkillStore()
    skillStore.versions = [
      version('stable-v1', 'STABLE'),
      version('candidate-v2', 'CANDIDATE'),
      version('draft-v3', 'DRAFT'),
    ]
    vi.spyOn(skillStore, 'load').mockResolvedValue()

    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia] } })

    const stable = wrapper.get<HTMLSelectElement>('[data-test="stable-version"]')
    const candidate = wrapper.get<HTMLSelectElement>('[data-test="candidate-version"]')
    expect(stable.element.tagName).toBe('SELECT')
    expect(candidate.element.tagName).toBe('SELECT')
    expect(stable.text()).toContain('stable-v1')
    expect(stable.text()).not.toContain('candidate-v2')
    expect(candidate.text()).toContain('candidate-v2')
    expect(candidate.text()).not.toContain('draft-v3')
  })

  it('版本汇总排除 DRAFT，且没有共同评测的冻结版本仍可选择', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useEvaluationStore()
    const skillStore = useSkillStore()
    skillStore.versions = [
      version('stable-v1', 'STABLE'),
      version('candidate-v2', 'CANDIDATE'),
      version('draft-v3', 'DRAFT'),
    ]
    vi.spyOn(skillStore, 'load').mockResolvedValue()
    const compare = vi.spyOn(store, 'compareVersions').mockImplementation(async () => {
      store.comparison = {
        comparable: false,
        leftVersionId: 'stable-v1',
        rightVersionId: 'candidate-v2',
        evaluationRunId: null,
        reasons: ['暂无共同评测'],
        metricDeltas: {},
        sampleOutcomes: {},
        failureTypeChanges: {},
      }
    })
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia] } })

    await wrapper.get('[data-test="evaluation-tab-version"]').trigger('click')
    expect(wrapper.get('[data-test="summary-version"]').text()).toContain('stable-v1')
    expect(wrapper.get('[data-test="summary-version"]').text()).not.toContain('draft-v3')

    await wrapper.get('[data-test="evaluation-tab-compare"]').trigger('click')
    await wrapper.get('[data-test="compare-left-version"]').setValue('stable-v1')
    await wrapper.get('[data-test="compare-right-version"]').setValue('candidate-v2')
    await wrapper.get('[data-test="compare-versions"]').trigger('click')

    expect(compare).toHaveBeenCalledWith('stable-v1', 'candidate-v2')
    expect(wrapper.text()).toContain('暂无共同评测')
  })

  it.each(['PENDING', 'RUNNING'] as const)('从 evaluationId 恢复 %s 评测，并在 5 秒后刷新', async (status) => {
    vi.useFakeTimers()
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = evaluationRouter()
    await router.push('/admin/evaluations?evaluationId=evaluation-running')
    await router.isReady()
    const store = useEvaluationStore()
    const skillStore = useSkillStore()
    store.evaluation = run('evaluation-running', status)
    vi.spyOn(skillStore, 'load').mockResolvedValue()
    vi.spyOn(store, 'loadHistory').mockResolvedValue()
    const refresh = vi.spyOn(store, 'refreshEvaluation').mockResolvedValue()
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(refresh).toHaveBeenCalledWith('evaluation-running')
    refresh.mockClear()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(refresh).toHaveBeenCalledWith('evaluation-running')

    wrapper.unmount()
    refresh.mockClear()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(refresh).not.toHaveBeenCalled()
    vi.useRealTimers()
  })

  it('轮询刷新到终态后会停止定时器', async () => {
    vi.useFakeTimers()
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = evaluationRouter()
    await router.push('/admin/evaluations?evaluationId=evaluation-completed')
    await router.isReady()
    const store = useEvaluationStore()
    const skillStore = useSkillStore()
    store.evaluation = run('evaluation-completed', 'RUNNING')
    vi.spyOn(skillStore, 'load').mockResolvedValue()
    vi.spyOn(store, 'loadHistory').mockResolvedValue()
    let refreshCount = 0
    const refresh = vi.spyOn(store, 'refreshEvaluation').mockImplementation(async () => {
      refreshCount += 1
      if (refreshCount === 2) store.evaluation = run('evaluation-completed', 'COMPLETED')
    })
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia, router] } })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(refresh).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(refresh).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    vi.useRealTimers()
  })

  it('慢轮询未完成时不会启动第二个刷新', async () => {
    vi.useFakeTimers()
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = evaluationRouter()
    await router.push('/admin/evaluations?evaluationId=evaluation-slow')
    await router.isReady()
    const store = useEvaluationStore()
    const skillStore = useSkillStore()
    store.evaluation = run('evaluation-slow', 'RUNNING')
    vi.spyOn(skillStore, 'load').mockResolvedValue()
    vi.spyOn(store, 'loadHistory').mockResolvedValue()
    const slowRefresh = deferred<void>()
    const refresh = vi.spyOn(store, 'refreshEvaluation')
      .mockResolvedValueOnce()
      .mockReturnValueOnce(slowRefresh.promise)
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia, router] } })
    await flushPromises()

    await vi.advanceTimersByTimeAsync(5_000)
    expect(refresh).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(refresh).toHaveBeenCalledTimes(2)

    slowRefresh.resolve()
    await flushPromises()
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('切换评测后晚到的 URL 刷新不会重新轮询旧评测', async () => {
    vi.useFakeTimers()
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = evaluationRouter()
    await router.push('/admin/evaluations?evaluationId=evaluation-old')
    await router.isReady()
    const store = useEvaluationStore()
    const skillStore = useSkillStore()
    store.evaluation = run('evaluation-old', 'RUNNING')
    store.history = [run('evaluation-new', 'RUNNING')]
    vi.spyOn(skillStore, 'load').mockResolvedValue()
    vi.spyOn(store, 'loadHistory').mockResolvedValue()
    const oldRefresh = deferred<void>()
    const refresh = vi.spyOn(store, 'refreshEvaluation').mockImplementation(async (id) => {
      if (id === 'evaluation-old') return oldRefresh.promise
      store.evaluation = run('evaluation-new', 'RUNNING')
    })
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia, router] } })
    await Promise.resolve()

    await wrapper.get('.history-row').trigger('click')
    await flushPromises()
    oldRefresh.resolve()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(5_000)

    expect(refresh.mock.calls.filter(([id]) => id === 'evaluation-old')).toHaveLength(1)

    wrapper.unmount()
    vi.useRealTimers()
  })

  it('较早评测的慢响应不会覆盖已选择的新评测', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useEvaluationStore()
    const oldDetail = deferred<Response>()
    const fetchMock = vi.fn()
      .mockReturnValueOnce(oldDetail.promise)
      .mockResolvedValueOnce(response(run('evaluation-new', 'COMPLETED')))
      .mockResolvedValueOnce(response([]))
      .mockResolvedValueOnce(response([]))
    vi.stubGlobal('fetch', fetchMock)

    const oldRequest = store.refreshEvaluation('evaluation-old')
    const newRequest = store.refreshEvaluation('evaluation-new')
    await newRequest
    oldDetail.resolve(response(run('evaluation-old', 'RUNNING')))
    await oldRequest

    expect(store.evaluation?.id).toBe('evaluation-new')
    vi.unstubAllGlobals()
  })

  it('创建评测会取消过期刷新占用的忙碌状态', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useEvaluationStore()
    const oldDetail = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn()
      .mockReturnValueOnce(oldDetail.promise)
      .mockResolvedValueOnce(response(run('evaluation-created', 'PENDING')))
      .mockResolvedValueOnce(response([]))
      .mockResolvedValueOnce(response([])))

    const staleRefresh = store.refreshEvaluation('evaluation-old')
    await store.start('public-tech-2024-v3', ['BASELINE', 'candidate-v1'])

    expect(store.refreshing).toBe(false)
    expect(store.busy).toBe(false)

    oldDetail.resolve(response(run('evaluation-old', 'RUNNING')))
    await staleRefresh
    vi.unstubAllGlobals()
  })
})
