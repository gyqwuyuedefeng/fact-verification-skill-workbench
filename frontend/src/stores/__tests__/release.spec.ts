import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getCurrentRelease, getReleaseHistory } from '../../api/release'
import { useReleaseStore } from '../release'

vi.mock('../../api/release', () => ({
  changeRelease: vi.fn(),
  getCurrentRelease: vi.fn(),
  getReleaseHistory: vi.fn(),
  getShadowHistory: vi.fn(),
  registerCandidate: vi.fn(),
  reviewShadowRun: vi.fn(),
}))

describe('release store', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('没有任何发布历史时直接保持空态，不请求不存在的 current', async () => {
    vi.mocked(getReleaseHistory).mockResolvedValue([])
    const store = useReleaseStore()

    await store.load()

    expect(store.history).toEqual([])
    expect(store.current).toBeNull()
    expect(getCurrentRelease).not.toHaveBeenCalled()
  })
})
