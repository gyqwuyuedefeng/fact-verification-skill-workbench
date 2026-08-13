import { defineStore } from 'pinia'

import {
  changeRelease,
  getCurrentRelease,
  getReleaseHistory,
  getShadowHistory,
  registerCandidate,
  reviewShadowRun,
} from '../api/release'
import type { ReleaseState, ShadowHistory } from '../types/release'

const requestId = (prefix: string) => `${prefix}-${crypto.randomUUID()}`

export const useReleaseStore = defineStore('release', {
  state: () => ({
    current: null as ReleaseState | null,
    history: [] as ReleaseState[],
    shadowHistory: null as ShadowHistory | null,
    busy: false,
    error: null as string | null,
  }),
  actions: {
    async load() {
      this.history = await getReleaseHistory()
      if (!this.history.length) {
        this.current = null
        return
      }
      this.current = await getCurrentRelease()
    },
    async loadShadowHistory(reviewStatus?: string, versionId?: string) {
      this.shadowHistory = await getShadowHistory(reviewStatus, versionId)
    },
    async register(candidateVersionId: string, evaluationRunId: string, reason: string) {
      await this.execute(() =>
        registerCandidate(
          requestId('release-register'),
          candidateVersionId,
          evaluationRunId,
          reason,
        ),
      )
    },
    async change(action: 'shadow/start' | 'shadow/stop' | 'promote' | 'rollback', reason: string) {
      await this.execute(() => changeRelease(requestId('release-change'), action, reason))
    },
    async reviewShadow(runId: string, status: 'PASS' | 'FAIL', reason: string) {
      await reviewShadowRun(requestId('shadow-review'), runId, status, reason)
      await this.loadShadowHistory()
    },
    async execute(operation: () => Promise<ReleaseState>) {
      this.busy = true
      this.error = null
      try {
        this.current = await operation()
        this.history = await getReleaseHistory()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '发布操作失败'
        throw error
      } finally {
        this.busy = false
      }
    },
  },
})
