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
    error: null as string | null,
  }),
  actions: {
    async start(datasetVersion: string, variantIds: string[]) {
      this.busy = true
      this.error = null
      this.samples = []
      try {
        this.evaluation = await createEvaluation(requestId(), datasetVersion, variantIds)
        await this.loadHistory()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '评测创建失败'
        throw error
      } finally {
        this.busy = false
      }
    },
    async refresh() {
      if (!this.evaluation) return
      this.busy = true
      try {
        this.evaluation = await getEvaluation(this.evaluation.id)
        if (this.evaluation.status === 'COMPLETED') {
          this.samples = await getEvaluationSamples(this.evaluation.id)
        }
      } finally {
        this.busy = false
      }
    },
    async loadHistory(versionId?: string) {
      this.history = await listEvaluations(versionId)
    },
    async selectEvaluation(evaluationId: string) {
      this.evaluation = await getEvaluation(evaluationId)
      this.samples = this.evaluation.status === 'COMPLETED'
        ? await getEvaluationSamples(evaluationId)
        : []
    },
    async loadVersionSummary(versionId: string) {
      this.versionSummary = await getVersionEvaluationSummary(versionId)
    },
    async compareVersions(leftVersionId: string, rightVersionId: string) {
      this.comparison = await compareEvaluationVersions(leftVersionId, rightVersionId)
    },
  },
})
