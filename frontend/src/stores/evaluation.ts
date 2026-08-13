import { defineStore } from 'pinia'

import {
  compareEvaluationVersions,
  createEvaluation,
  getEvaluation,
  getEvaluationSamples,
  getVersionEvaluationSummary,
  listEvaluations,
} from '../api/evaluation'
import type {
  EvaluationComparison,
  EvaluationRun,
  EvaluationSample,
  SkillEvaluationSummary,
} from '../types/evaluation'

function requestId(): string {
  return `evaluation-${crypto.randomUUID()}`
}

export const useEvaluationStore = defineStore('evaluation', {
  state: () => ({
    evaluation: null as EvaluationRun | null,
    samples: [] as EvaluationSample[],
    history: [] as EvaluationRun[],
    versionSummary: null as SkillEvaluationSummary | null,
    comparison: null as EvaluationComparison | null,
    busy: false,
    creating: false,
    refreshing: false,
    refreshGeneration: 0,
    error: null as string | null,
  }),
  actions: {
    async start(datasetVersion: string, variantIds: string[]): Promise<EvaluationRun> {
      this.refreshGeneration += 1
      this.refreshing = false
      this.creating = true
      this.busy = true
      this.error = null
      this.samples = []
      try {
        const created = await createEvaluation(requestId(), datasetVersion, variantIds)
        this.evaluation = created
        await this.loadHistory()
        return created
      } catch (error) {
        this.error = error instanceof Error ? error.message : '评测创建失败'
        throw error
      } finally {
        this.creating = false
        this.busy = this.refreshing
      }
    },
    async refreshEvaluation(evaluationId: string): Promise<void> {
      const generation = ++this.refreshGeneration
      this.refreshing = true
      this.busy = true
      try {
        const evaluation = await getEvaluation(evaluationId)
        const samples = await getEvaluationSamples(evaluationId)
        if (this.refreshGeneration !== generation) return
        this.evaluation = evaluation
        this.samples = samples
      } finally {
        if (this.refreshGeneration === generation) {
          this.refreshing = false
          this.busy = this.creating
        }
      }
    },
    async refresh(): Promise<void> {
      if (!this.evaluation) return
      await this.refreshEvaluation(this.evaluation.id)
    },
    async loadHistory(versionId?: string) {
      this.history = await listEvaluations(versionId)
    },
    async selectEvaluation(evaluationId: string) {
      await this.refreshEvaluation(evaluationId)
    },
    async loadVersionSummary(versionId: string) {
      this.versionSummary = await getVersionEvaluationSummary(versionId)
    },
    async compareVersions(leftVersionId: string, rightVersionId: string) {
      this.comparison = await compareEvaluationVersions(leftVersionId, rightVersionId)
    },
  },
})
