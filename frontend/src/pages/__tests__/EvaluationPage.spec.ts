import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import EvaluationPage from '../EvaluationPage.vue'
import { useEvaluationStore } from '../../stores/evaluation'

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
    expect(wrapper.text()).toContain('金标 VERIFIED')
    expect(wrapper.text()).toContain('BASELINE')
    expect(wrapper.text()).toContain('CONFLICT')
    expect(wrapper.text()).toContain('评分失败')
    expect(wrapper.text()).toContain('导出报告')
  })

  it('以 BASELINE、Stable、Candidate 创建评测', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useEvaluationStore()
    const start = vi.spyOn(store, 'start').mockResolvedValue()
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia] } })

    await wrapper.find('[data-test="stable-id"]').setValue('stable-v1')
    await wrapper.find('[data-test="candidate-id"]').setValue('candidate-v2')
    await wrapper.find('[data-test="start-evaluation"]').trigger('click')

    expect(start).toHaveBeenCalledWith('public-tech-2024-v3', [
      'BASELINE',
      'stable-v1',
      'candidate-v2',
    ])
  })

  it('首次建立 Stable 时只以 BASELINE 和首个 Candidate 创建评测', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useEvaluationStore()
    const start = vi.spyOn(store, 'start').mockResolvedValue()
    const wrapper = mount(EvaluationPage, { global: { plugins: [pinia] } })

    await wrapper.find('[data-test="initial-stable"]').setValue(true)
    await wrapper.find('[data-test="candidate-id"]').setValue('candidate-v1')
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
})
