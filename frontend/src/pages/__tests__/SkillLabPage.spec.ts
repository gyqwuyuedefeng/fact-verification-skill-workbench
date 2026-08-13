import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SkillLabPage from '../SkillLabPage.vue'
import { useSkillStore } from '../../stores/skill'

describe('SkillLabPage', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('展示 DRAFT 编辑、冻结版本号、版本卡和注册门禁提示', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useSkillStore()
    store.versions = [
      {
        id: 'candidate-1',
        skillKey: 'company-material-fact-check',
        version: 'v0.1.0+abcdef',
        parentVersionId: null,
        status: 'CANDIDATE',
        contentHash: 'a'.repeat(64),
        changeSummary: '修复主体歧义',
        createdAt: '2026-08-12T00:00:00Z',
        frozenAt: '2026-08-12T00:01:00Z',
      },
    ]
    store.card = {
      skillKey: 'company-material-fact-check',
      version: 'v0.1.0+abcdef',
      status: 'CANDIDATE',
      parentVersion: null,
      contentHash: 'a'.repeat(64),
      changeSummary: '修复主体歧义',
      evaluationRunId: null,
      metrics: null,
      gateStatus: 'PENDING',
      knownFailures: [],
    }

    const wrapper = mount(SkillLabPage, { global: { plugins: [pinia] } })

    expect(wrapper.text()).toContain('DRAFT')
    expect(wrapper.text()).toContain('v0.1.0+abcdef')
    expect(wrapper.text()).toContain('版本卡')
    expect(wrapper.text()).toContain('完成同条件评测后才能注册')
  })

  it('可以创建草稿并冻结当前草稿', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useSkillStore()
    const draft = {
      id: 'draft-1',
      skillKey: 'company-material-fact-check',
      version: null,
      parentVersionId: null,
      status: 'DRAFT' as const,
      contentHash: null,
      changeSummary: '修复主体歧义',
      createdAt: '2026-08-12T00:00:00Z',
      frozenAt: null,
    }
    const createDraft = vi.spyOn(store, 'createDraft').mockImplementation(async () => {
      store.currentDraft = draft
      store.selectedContent = {
        id: draft.id,
        parentVersionId: null,
        status: 'DRAFT',
        editable: true,
        skillMarkdown: '# 初始 Skill',
        references: [],
        changeSummary: draft.changeSummary,
      }
    })
    const freezeDraft = vi.spyOn(store, 'freezeCurrent').mockResolvedValue()
    const wrapper = mount(SkillLabPage, { global: { plugins: [pinia] } })

    await wrapper.find('[data-test="create-draft"]').trigger('click')
    await wrapper.vm.$nextTick()
    await wrapper.find('[data-test="freeze-draft"]').trigger('click')

    expect(createDraft).toHaveBeenCalled()
    expect(freezeDraft).toHaveBeenCalled()
  })

  it('新建草稿后自动载入源 Skill，并可从已选冻结版本克隆', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useSkillStore()
    store.versions = [{
      id: 'stable-1',
      skillKey: 'company-material-fact-check',
      version: 'v0.1.0+stable',
      parentVersionId: null,
      status: 'STABLE',
      contentHash: 'a'.repeat(64),
      changeSummary: '初始规则',
      createdAt: '2026-08-12T00:00:00Z',
      frozenAt: '2026-08-12T00:01:00Z',
    }]
    store.selectedVersionId = 'stable-1'
    const createDraft = vi.spyOn(store, 'createDraft').mockImplementation(async () => {
      store.currentDraft = {
        id: 'draft-2',
        skillKey: 'company-material-fact-check',
        version: null,
        parentVersionId: 'stable-1',
        status: 'DRAFT',
        contentHash: null,
        changeSummary: '修复规则',
        createdAt: '2026-08-13T00:00:00Z',
        frozenAt: null,
      }
      store.selectedContent = {
        id: 'draft-2',
        parentVersionId: 'stable-1',
        status: 'DRAFT',
        editable: true,
        skillMarkdown: '# 从 Stable 克隆的 Skill',
        references: [{ path: 'references/rules.md', content: '# Rules' }],
        changeSummary: '修复规则',
      }
    })
    const wrapper = mount(SkillLabPage, { global: { plugins: [pinia] } })

    await wrapper.find('[data-test="create-draft"]').trigger('click')
    await wrapper.vm.$nextTick()

    expect(createDraft).toHaveBeenCalledWith('stable-1', '修复主体歧义或已知失败样本')
    expect(wrapper.find('textarea.code-editor').element.value).toContain('从 Stable 克隆的 Skill')
    expect(wrapper.find('[data-test="create-draft"]').text()).toContain('从已选版本克隆')
  })

  it('已有冻结版本但尚未选择时禁止误建第二个根草稿', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useSkillStore()
    store.versions = [{
      id: 'candidate-1',
      skillKey: 'company-material-fact-check',
      version: 'v0.1.0+candidate',
      parentVersionId: null,
      status: 'CANDIDATE',
      contentHash: 'a'.repeat(64),
      changeSummary: '初始规则',
      createdAt: '2026-08-12T00:00:00Z',
      frozenAt: '2026-08-12T00:01:00Z',
    }]

    const wrapper = mount(SkillLabPage, { global: { plugins: [pinia] } })
    const createButton = wrapper.find('[data-test="create-draft"]')

    expect(createButton.attributes('disabled')).toBeDefined()
    expect(createButton.text()).toContain('请先选择一个版本')
  })

  it('点击冻结历史版本后加载完整正文并保持只读', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useSkillStore()
    store.versions = [{
      id: 'candidate-readonly',
      skillKey: 'company-material-fact-check',
      version: 'v0.1.0+readonly',
      parentVersionId: null,
      status: 'CANDIDATE',
      contentHash: 'c'.repeat(64),
      changeSummary: '冻结内容只读展示',
      createdAt: '2026-08-12T00:00:00Z',
      frozenAt: '2026-08-12T00:01:00Z',
    }]
    vi.spyOn(store, 'selectVersion').mockImplementation(async () => {
      store.selectedVersionId = 'candidate-readonly'
      store.currentDraft = null
      store.selectedContent = {
        id: 'candidate-readonly',
        parentVersionId: null,
        status: 'CANDIDATE',
        editable: false,
        skillMarkdown: '# 冻结历史 Skill\n必须展示 references。',
        references: [{ path: 'references/rules.md', content: '# 冻结规则' }],
        changeSummary: '冻结内容只读展示',
      }
    })
    const wrapper = mount(SkillLabPage, { global: { plugins: [pinia] } })

    await wrapper.find('.version-row').trigger('click')
    await wrapper.vm.$nextTick()

    const editors = wrapper.findAll('textarea.code-editor')
    expect(editors[0].element.value).toContain('冻结历史 Skill')
    expect(editors[0].attributes('readonly')).toBeDefined()
    expect(editors[1].element.value).toContain('冻结规则')
    expect(editors[1].attributes('readonly')).toBeDefined()
    expect(wrapper.text()).toContain('版本内容（只读）')
    expect(wrapper.find('[data-test="delete-draft"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="freeze-draft"]').exists()).toBe(false)
  })

  it('删除 DRAFT 前二次确认，取消时不调用删除，确认后才清理', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useSkillStore()
    store.currentDraft = {
      id: 'draft-delete',
      skillKey: 'company-material-fact-check',
      version: null,
      parentVersionId: 'stable-1',
      status: 'DRAFT',
      contentHash: null,
      changeSummary: '待删除测试草稿',
      createdAt: '2026-08-13T00:00:00Z',
      frozenAt: null,
    }
    store.selectedContent = {
      id: 'draft-delete',
      parentVersionId: 'stable-1',
      status: 'DRAFT',
      editable: true,
      skillMarkdown: '# 待删除草稿',
      references: [],
      changeSummary: '待删除测试草稿',
    }
    const deleteDraft = vi.spyOn(store, 'deleteCurrentDraft').mockResolvedValue()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    const wrapper = mount(SkillLabPage, { global: { plugins: [pinia] } })

    await wrapper.find('[data-test="delete-draft"]').trigger('click')
    expect(deleteDraft).not.toHaveBeenCalled()
    await wrapper.find('[data-test="delete-draft"]').trigger('click')

    expect(confirm).toHaveBeenCalledTimes(2)
    expect(deleteDraft).toHaveBeenCalledTimes(1)
  })

  it('由管理员手动生成与基准版本的确定性差异和 AI 升级说明', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useSkillStore()
    store.versions = [
      {
        id: 'stable-1',
        skillKey: 'company-material-fact-check',
        version: 'v0.1.0+stable',
        parentVersionId: null,
        status: 'STABLE',
        contentHash: 'a'.repeat(64),
        changeSummary: '初始规则',
        createdAt: '2026-08-11T00:00:00Z',
        frozenAt: '2026-08-11T00:01:00Z',
      },
      {
        id: 'candidate-2',
        skillKey: 'company-material-fact-check',
        version: 'v0.2.0+candidate',
        parentVersionId: 'stable-1',
        status: 'CANDIDATE',
        contentHash: 'b'.repeat(64),
        changeSummary: '统一金额单位',
        createdAt: '2026-08-12T00:00:00Z',
        frozenAt: '2026-08-12T00:01:00Z',
      },
    ]
    store.comparison = {
      targetVersionId: 'candidate-2',
      baseVersionId: 'stable-1',
      deterministicDiff: '-旧规则\n+新规则：金额统一转元',
      summaryStatus: 'COMPLETED',
      modelId: 'gpt-5.6',
      generatedAt: '2026-08-12T00:02:00Z',
      persisted: true,
      generatedSummary: {
        headline: '强化单位归一化',
        changes: ['万元统一转元'],
        reviewRisks: ['关注历史材料单位'],
      },
      advisory: '模型生成、仅供审核参考',
      errorCode: null,
    }
    vi.spyOn(store, 'loadComparison').mockResolvedValue()
    const compare = vi.spyOn(store, 'generateComparison').mockResolvedValue()

    const wrapper = mount(SkillLabPage, { global: { plugins: [pinia] } })
    await wrapper.find('[data-test="target-version"]').setValue('candidate-2')
    await wrapper.find('[data-test="base-version"]').setValue('stable-1')
    await wrapper.find('[data-test="generate-change-summary"]').trigger('click')

    expect(compare).toHaveBeenCalledWith('candidate-2', 'stable-1')
    expect(wrapper.text()).toContain('强化单位归一化')
    expect(wrapper.text()).toContain('模型生成、仅供审核参考')
    expect(wrapper.find('[data-test="deterministic-diff"]').text()).toContain('+新规则')
  })

  it('选择版本对时先恢复已保存说明，只有点击按钮才生成新说明', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useSkillStore()
    store.versions = [
      {
        id: 'stable-1', skillKey: 'company-material-fact-check', version: 'v0.1.0', parentVersionId: null,
        status: 'STABLE', contentHash: 'a'.repeat(64), changeSummary: '初始规则',
        createdAt: '2026-08-11T00:00:00Z', frozenAt: '2026-08-11T00:01:00Z',
      },
      {
        id: 'candidate-1', skillKey: 'company-material-fact-check', version: 'v0.2.0', parentVersionId: 'stable-1',
        status: 'CANDIDATE', contentHash: 'b'.repeat(64), changeSummary: '候选规则',
        createdAt: '2026-08-12T00:00:00Z', frozenAt: '2026-08-12T00:01:00Z',
      },
    ]
    const loadComparison = vi.fn().mockResolvedValue(undefined)
    const generateComparison = vi.fn().mockResolvedValue(undefined)
    Object.assign(store, { loadComparison, generateComparison })
    const wrapper = mount(SkillLabPage, { global: { plugins: [pinia] } })

    await wrapper.get('[data-test="target-version"]').setValue('candidate-1')
    await wrapper.get('[data-test="base-version"]').setValue('stable-1')

    expect(loadComparison).toHaveBeenCalledWith('candidate-1', 'stable-1')
    expect(generateComparison).not.toHaveBeenCalled()

    await wrapper.get('[data-test="generate-change-summary"]').trigger('click')

    expect(generateComparison).toHaveBeenCalledWith('candidate-1', 'stable-1')
  })

  it('生成升级说明失败时保留已有摘要并提示失败原因', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useSkillStore()
    store.versions = [
      {
        id: 'stable-1', skillKey: 'company-material-fact-check', version: 'v0.1.0', parentVersionId: null,
        status: 'STABLE', contentHash: 'a'.repeat(64), changeSummary: '初始规则',
        createdAt: '2026-08-11T00:00:00Z', frozenAt: '2026-08-11T00:01:00Z',
      },
      {
        id: 'candidate-1', skillKey: 'company-material-fact-check', version: 'v0.2.0', parentVersionId: 'stable-1',
        status: 'CANDIDATE', contentHash: 'b'.repeat(64), changeSummary: '候选规则',
        createdAt: '2026-08-12T00:00:00Z', frozenAt: '2026-08-12T00:01:00Z',
      },
    ]
    store.comparison = {
      targetVersionId: 'candidate-1', baseVersionId: 'stable-1', deterministicDiff: '-旧规则\n+新规则',
      summaryStatus: 'COMPLETED', modelId: 'gpt-5.6', generatedAt: '2026-08-12T00:02:00Z', persisted: true,
      generatedSummary: { headline: '已有升级摘要', changes: ['统一金额单位'], reviewRisks: ['复核历史材料'] },
      advisory: '模型生成、仅供审核参考', errorCode: null,
    }
    const generateComparison = vi.fn().mockImplementation(async () => {
      store.error = '模型服务暂时不可用'
    })
    Object.assign(store, { loadComparison: vi.fn().mockResolvedValue(undefined), generateComparison })
    const wrapper = mount(SkillLabPage, { global: { plugins: [pinia] } })

    await wrapper.get('[data-test="target-version"]').setValue('candidate-1')
    await wrapper.get('[data-test="base-version"]').setValue('stable-1')
    await wrapper.get('[data-test="generate-change-summary"]').trigger('click')

    expect(wrapper.text()).toContain('已有升级摘要')
    expect(wrapper.text()).toContain('模型服务暂时不可用')
  })
})
