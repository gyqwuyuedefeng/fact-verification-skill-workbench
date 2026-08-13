import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import DemoStatePage from '../DemoStatePage.vue'
import { useDemoStore } from '../../stores/demo'

const tableCounts = {
  claim: 0,
  verification_run: 0,
  verification_task: 0,
  evidence_snapshot: 0,
  release_binding: 0,
  skill_version: 0,
  evaluation_run: 0,
}

const state = (counts = tableCounts) => ({
  tableCounts: counts,
  storageEmpty: { uploads: true, 'skill-snapshots': true, 'skill-runtime': true },
})

function response(body: unknown, ok = true): Response {
  return { ok, status: ok ? 200 : 503, json: async () => body } as Response
}

function mountPage() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(DemoStatePage, { global: { plugins: [pinia] } })
}

async function selectZip(wrapper: ReturnType<typeof mount>) {
  const input = wrapper.get('[data-test="snapshot-file"]')
  Object.defineProperty(input.element, 'files', {
    configurable: true,
    value: [new File(['zip'], 'state.zip', { type: 'application/zip' })],
  })
  await input.trigger('change')
}

describe('DemoStatePage', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(() => vi.unstubAllGlobals())

  it('展示从零现场演示的十步向导与固定脱敏内置导入提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(state())))

    const wrapper = mountPage()
    await vi.waitFor(() => expect(wrapper.text()).toContain('创建首个 Skill'))

    expect(wrapper.text()).toContain('从零现场演示（10 步）')
    expect(wrapper.text()).toContain('快速导入内置状态查看完整结果')
    expect(wrapper.text()).toContain('固定脱敏数据，不是本次现场重新生成')
  })

  it('清空首次仅请求确认，取消或短语不完全匹配时不调用 API', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(state()))
    vi.stubGlobal('fetch', fetchMock)
    const prompt = vi.spyOn(window, 'prompt').mockReturnValueOnce(null).mockReturnValueOnce('清空全部比赛数据 ')
    const wrapper = mountPage()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(useDemoStore().busy).toBe(false))

    await wrapper.get('[data-test="reset-demo-state"]').trigger('click')
    await wrapper.get('[data-test="reset-demo-state"]').trigger('click')

    expect(prompt).toHaveBeenCalledTimes(2)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('精确确认短语才调用 reset，成功后重新加载七表和三个目录状态', async () => {
    const afterReset = state({ ...tableCounts, skill_version: 1 })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(state()))
      .mockResolvedValueOnce(response(state()))
      .mockResolvedValueOnce(response(afterReset))
    vi.stubGlobal('fetch', fetchMock)
    vi.spyOn(window, 'prompt').mockReturnValue('清空全部比赛数据')
    const wrapper = mountPage()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(useDemoStore().busy).toBe(false))

    await wrapper.get('[data-test="reset-demo-state"]').trigger('click')
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))

    const [, init] = fetchMock.mock.calls[1] ?? []
    expect(init?.method).toBe('POST')
    expect(init?.headers).toMatchObject({ 'Content-Type': 'application/json' })
    expect(wrapper.text()).toContain('Skill 版本（skill_version）')
    expect(wrapper.text()).toContain('上传材料目录')
  })

  it('导出前提示原始企业附件可能敏感', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(state())))
    const wrapper = mountPage()
    await vi.waitFor(() => expect(wrapper.text()).toContain('导出演示快照'))

    expect(wrapper.text()).toContain('包含原始企业附件，可能涉及敏感数据')
  })

  it('自定义快照仅接受 ZIP，且状态非空时禁用导入', async () => {
    const nonBlank = state({ ...tableCounts, claim: 1 })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(nonBlank)))
    const wrapper = mountPage()
    await vi.waitFor(() => expect(wrapper.text()).toContain('主张（claim）'))

    const input = wrapper.get('[data-test="snapshot-file"]')
    expect(input.attributes('accept')).toBe('.zip,application/zip')
    expect(wrapper.get('[data-test="import-snapshot"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="import-builtin"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('请先清空')
  })

  it('内置演示仅在严格空状态可用，并发送安全随机幂等键', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(state()))
      .mockResolvedValueOnce(response(state({ ...tableCounts, skill_version: 4 })))
      .mockResolvedValueOnce(response(state({ ...tableCounts, skill_version: 4 })))
    vi.stubGlobal('fetch', fetchMock)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = mountPage()
    await vi.waitFor(() => expect(useDemoStore().busy).toBe(false))

    expect(wrapper.get('[data-test="import-builtin"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[data-test="import-builtin"]').trigger('click')
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))

    const [, init] = fetchMock.mock.calls[1] ?? []
    expect(init).toMatchObject({ method: 'POST' })
    expect(init?.headers).toMatchObject({
      'X-Confirmation-Phrase': '导入内置演示数据',
      'Idempotency-Key': expect.stringMatching(/^demo-import-builtin-[A-Za-z0-9-]{20,}$/),
    })
  })

  it.each([
    ['缺少固定表', () => {
      const counts = { ...tableCounts }
      delete (counts as Record<string, number>).claim
      return counts
    }],
    ['空表集合', () => ({})],
    ['含未知表', () => ({ ...tableCounts, unexpected_table: 0 })],
    ['计数为负数', () => ({ ...tableCounts, claim: -1 })],
    ['目录状态不是 boolean', () => tableCounts, () => ({ uploads: 'true', 'skill-snapshots': true, 'skill-runtime': true })],
  ])('状态合同%s时拒绝自定义导入', async (_name, countsFactory, directoriesFactory = () => state().storageEmpty) => {
    const counts = countsFactory()
    const directories = directoriesFactory()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ tableCounts: counts, storageEmpty: directories })))
    const wrapper = mountPage()
    const store = useDemoStore()
    await vi.waitFor(() => expect(store.busy).toBe(false))

    expect(store.isBlank).toBe(false)
    expect(wrapper.text()).toContain('状态合同异常/无法安全导入')
    expect(wrapper.get('[data-test="import-snapshot"]').attributes('disabled')).toBeDefined()
  })

  it('固定七表和三个目录均合法且为空时才允许自定义 ZIP 导入', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(state())))
    const wrapper = mountPage()
    const store = useDemoStore()
    await vi.waitFor(() => expect(store.busy).toBe(false))

    await selectZip(wrapper)

    expect(store.isBlank).toBe(true)
    expect(wrapper.text()).not.toContain('状态合同异常/无法安全导入')
    expect(wrapper.get('[data-test="import-snapshot"]').attributes('disabled')).toBeUndefined()
  })

  it.each([
    ['顶层字段缺失', {}],
    ['顶层字段为 null', { tableCounts: null, storageEmpty: null }],
  ])('状态%s时仍安全展示异常并禁用导入', async (_name, body) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(body)))
    const wrapper = mountPage()
    const store = useDemoStore()
    await vi.waitFor(() => expect(store.busy).toBe(false))

    expect(wrapper.text()).toContain('状态合同异常/无法安全导入')
    expect(wrapper.text()).toContain('未知')
    expect(wrapper.get('[data-test="import-snapshot"]').attributes('disabled')).toBeDefined()
  })

  it('导入成功后通过真实 store action 重新加载状态', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(state()))
      .mockResolvedValueOnce(response(state()))
      .mockResolvedValueOnce(response(state({ ...tableCounts, claim: 2 })))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPage()
    const store = useDemoStore()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(store.busy).toBe(false))

    await selectZip(wrapper)
    await wrapper.get('[data-test="import-snapshot"]').trigger('click')
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    await vi.waitFor(() => expect(store.busy).toBe(false))

    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: 'POST' })
    expect(store.state?.tableCounts.claim).toBe(2)
  })

  it('导入失败时展示后端返回的中文提示', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(state()))
      .mockResolvedValueOnce(response({ message: '快照格式不正确' }, false))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mountPage()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(useDemoStore().busy).toBe(false))

    await selectZip(wrapper)
    await wrapper.get('[data-test="import-snapshot"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('快照格式不正确'))
  })
})
