import type {
  EvaluationComparison,
  EvaluationRun,
  EvaluationSample,
  SkillEvaluationSummary,
} from '../types/evaluation'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Error(error?.message ?? `请求失败（${response.status}）`)
  }
  return (await response.json()) as T
}

export function createEvaluation(
  requestId: string,
  datasetVersion: string,
  variantIds: string[],
): Promise<EvaluationRun> {
  return request('/api/evaluations', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': requestId,
    },
    body: JSON.stringify({ datasetVersion, variantIds }),
  })
}

export function getEvaluation(evaluationId: string): Promise<EvaluationRun> {
  return request(`/api/evaluations/${evaluationId}`)
}

export function getEvaluationSamples(evaluationId: string): Promise<EvaluationSample[]> {
  return request(`/api/evaluations/${evaluationId}/samples`)
}

export function listEvaluations(versionId?: string): Promise<EvaluationRun[]> {
  const query = versionId ? `?versionId=${encodeURIComponent(versionId)}` : ''
  return request(`/api/evaluations${query}`)
}

export function getVersionEvaluationSummary(versionId: string): Promise<SkillEvaluationSummary> {
  return request(`/api/evaluations/version-summary/${versionId}`)
}

export function compareEvaluationVersions(
  leftVersionId: string,
  rightVersionId: string,
): Promise<EvaluationComparison> {
  const query = new URLSearchParams({ leftVersionId, rightVersionId })
  return request(`/api/evaluations/comparison?${query.toString()}`)
}

export function reportUrl(evaluationId: string, format: 'json' | 'markdown'): string {
  return `/api/evaluations/${evaluationId}/report?format=${format}`
}
