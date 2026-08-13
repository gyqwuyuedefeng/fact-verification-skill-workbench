export interface MetricValue {
  definition: string
  numerator: number
  denominator: number
  value: number
}

export interface CoreMetrics {
  accuracy: MetricValue
  completionRate: MetricValue
  stability: MetricValue
  humanInterventionRate: MetricValue
}

export interface EvaluationVariant {
  type: 'BASELINE' | 'SKILL'
  identifier: string
  contentHash: string
}

export interface GateCheck {
  name: string
  passed: boolean
  reason: string
}

export interface EvaluationRun {
  id: string
  datasetVersion: string
  datasetHash: string | null
  sampleCount: number
  variants: EvaluationVariant[] | null
  runManifest: Record<string, unknown> | null
  metrics: Record<string, CoreMetrics> | null
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'INTERRUPTED'
  gateStatus: 'PENDING' | 'PASS' | 'FAIL'
  gateReasons: GateCheck[] | null
  createdAt?: string | null
  finishedAt?: string | null
}

export interface EvaluationSample {
  sampleId: string
  gold?: {
    expectedStatus?: string
    material?: { text?: string }
  }
  variantResults: Record<string, {
    score?: {
      sampleId?: string
      accurate?: boolean
      completed?: boolean
      requiresHumanIntervention?: boolean
    }
    attempts?: Array<{
      attempt?: number
      output?: {
        claims?: Array<{ status?: string }>
        [key: string]: unknown
      } | null
      durationMs?: number
      errorCode?: string | null
    }>
  }>
}

export interface SkillEvaluationSummary {
  versionId: string
  evaluationCount: number
  latestEvaluationId: string | null
  registeredEvaluationId: string | null
  evaluations: EvaluationRun[]
}

export interface EvaluationComparison {
  comparable: boolean
  leftVersionId: string
  rightVersionId: string
  evaluationRunId: string | null
  reasons: string[]
  metricDeltas: Record<string, number>
  sampleOutcomes: Record<string, number>
  failureTypeChanges: Record<string, number>
}
