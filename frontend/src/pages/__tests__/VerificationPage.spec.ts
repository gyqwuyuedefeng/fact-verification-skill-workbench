import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import VerificationPage from '../VerificationPage.vue'
import { useVerificationStore } from '../../stores/verification'

describe('VerificationPage', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('展示对话输入、BASELINE/Stable 选择且不暴露影子能力', () => {
    const wrapper = mount(VerificationPage, { global: { plugins: [createPinia()] } })

    expect(wrapper.find('input[type="file"]').attributes('accept')).toContain('.md')
    expect(wrapper.find('input[type="file"]').attributes('accept')).toContain('.xlsx')
    expect(wrapper.find('[data-test="chat-message"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('通用基线')
    expect(wrapper.text()).toContain('当前 Stable')
    expect(wrapper.text()).not.toContain('影子 Candidate')
    expect(wrapper.text()).not.toContain('SHADOW')
  })

  it('按主张展示状态、原文位置和外部证据', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useVerificationStore()
    store.claims = [
      {
        id: 'claim-1',
        claimText: '2025 年营业收入为 1000 万元',
        materialLocator: { fileId: 'f1', sheet: '财务', cellRange: 'B2' },
        normalizedClaim: { metric: '营业收入', period: '2025', value: 1000, unit: '万元' },
        subject: { companyId: 'C001', companyName: '火石科技' },
        status: 'VERIFIED',
        riskFlags: [],
        evidence: [
          {
            dataset: 'ads_lget_company_revenue',
            recordId: 'r1',
            observedAt: '2026-08-12T00:00:00Z',
          },
        ],
        explanation: '材料与外部证据一致',
        requiresHumanIntervention: false,
      },
    ]
    const wrapper = mount(VerificationPage, { global: { plugins: [pinia] } })

    expect(wrapper.text()).toContain('已验证')
    expect(wrapper.text()).toContain('财务!B2')
    expect(wrapper.text()).toContain('ads_lget_company_revenue')
  })

  it('纯文字按回车调用 BASELINE 核验并创建 01/02/03 任务卡', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useVerificationStore()
    const start = vi.spyOn(store, 'startVerification').mockResolvedValue()
    const wrapper = mount(VerificationPage, { global: { plugins: [pinia] } })
    const input = wrapper.find<HTMLTextAreaElement>('[data-test="chat-message"]')
    await input.setValue('某模拟企业2025年营业收入为1.2亿元。')
    await input.trigger('keydown', { key: 'Enter' })

    expect(start).toHaveBeenCalledWith('某模拟企业2025年营业收入为1.2亿元。', null, 'BASELINE')
    expect(wrapper.text()).toContain('01 输入快照')
    expect(wrapper.text()).toContain('02 执行轨迹')
    expect(wrapper.text()).toContain('03 核验主张')
  })

  it('将连续模型文本增量折叠为一条可读的执行轨迹', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useVerificationStore()
    store.conversations = [
      {
        id: 'conversation-1',
        message: '核验模拟材料',
        fileName: null,
        executionMode: 'BASELINE',
        task: null,
        claims: [],
        events: [
          { type: 'RUN_CREATED', data: {} },
          { type: 'TEXT_DELTA', data: { text: '正在' } },
          { type: 'TEXT_DELTA', data: { text: '分析' } },
          { type: 'TEXT_DELTA', data: { text: '材料' } },
          { type: 'TOOL_STARTED', data: { tool: 'resolve_company' } },
          { type: 'TEXT_DELTA', data: { text: '继续' } },
          { type: 'TEXT_DELTA', data: { text: '分析' } },
        ],
        error: null,
      },
    ]

    const wrapper = mount(VerificationPage, { global: { plugins: [pinia] } })

    expect(wrapper.findAll('.live-timeline code').map((node) => node.text())).toEqual([
      'RUN_CREATED',
      'TEXT_DELTA',
      'TOOL_STARTED',
      'TEXT_DELTA',
    ])
  })

  it('轮询先观察到完成态时仍补齐 RUN_COMPLETED 终态轨迹', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useVerificationStore()
    store.conversations = [
      {
        id: 'conversation-terminal',
        message: '核验模拟材料',
        fileName: null,
        executionMode: 'BASELINE',
        task: {
          id: 'task-1',
          inputType: 'TEXT',
          messagePresent: true,
          executionMode: 'BASELINE',
          fileName: null,
          fileHash: null,
          documentSnapshotHash: 'a'.repeat(64),
          status: 'COMPLETED',
          primaryRunId: 'run-1',
          errorCode: null,
          createdAt: '2026-08-13T00:00:00Z',
        },
        claims: [],
        events: [{ type: 'RUN_CREATED', data: {} }],
        error: null,
      },
    ]

    const wrapper = mount(VerificationPage, { global: { plugins: [pinia] } })

    expect(wrapper.findAll('.live-timeline code').map((node) => node.text())).toEqual([
      'RUN_CREATED',
      'RUN_COMPLETED',
    ])
  })

  it('移除附件时同时清空原生文件输入以允许再次选择同一文件', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mount(VerificationPage, { global: { plugins: [pinia] } })
    const input = wrapper.find<HTMLInputElement>('input[type="file"]')
    const file = new File(['# 模拟材料'], 'same-file.md', { type: 'text/markdown' })
    let nativeValue = 'C:\\fakepath\\same-file.md'
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    Object.defineProperty(input.element, 'value', {
      configurable: true,
      get: () => nativeValue,
      set: (value: string) => { nativeValue = value },
    })
    await input.trigger('change')

    expect(wrapper.text()).toContain('same-file.md')
    await wrapper.get('button[aria-label="移除附件"]').trigger('click')

    expect(wrapper.text()).not.toContain('same-file.md')
    expect(nativeValue).toBe('')
  })
})
