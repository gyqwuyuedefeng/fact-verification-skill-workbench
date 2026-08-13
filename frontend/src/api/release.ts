import type { ReleaseState, ShadowHistory } from '../types/release'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Error(error?.message ?? `请求失败（${response.status}）`)
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return undefined as T
  }
  return (await response.json()) as T
}

const headers = (requestId: string) => ({
  'Content-Type': 'application/json',
  'Idempotency-Key': requestId,
})

export function getCurrentRelease(): Promise<ReleaseState> {
  return request('/api/releases/current')
}

export function getReleaseHistory(): Promise<ReleaseState[]> {
  return request('/api/releases/history')
}

export function registerCandidate(
  requestId: string,
  candidateVersionId: string,
  evaluationRunId: string,
  reason: string,
): Promise<ReleaseState> {
  return request('/api/releases/register', {
    method: 'POST',
    headers: headers(requestId),
    body: JSON.stringify({ candidateVersionId, evaluationRunId, reason }),
  })
}

export function changeRelease(
  requestId: string,
  action: 'shadow/start' | 'shadow/stop' | 'promote' | 'rollback',
  reason: string,
): Promise<ReleaseState> {
  return request(`/api/releases/${action}`, {
    method: 'POST',
    headers: headers(requestId),
    body: JSON.stringify({ reason }),
  })
}

export function getShadowHistory(
  reviewStatus?: string,
  versionId?: string,
): Promise<ShadowHistory> {
  const query = new URLSearchParams()
  if (reviewStatus) query.set('reviewStatus', reviewStatus)
  if (versionId) query.set('versionId', versionId)
  const suffix = query.size ? `?${query.toString()}` : ''
  return request(`/api/shadow-runs${suffix}`)
}

export function reviewShadowRun(
  requestId: string,
  runId: string,
  status: 'PASS' | 'FAIL',
  reason: string,
): Promise<void> {
  return request(`/api/runs/${runId}/review`, {
    method: 'POST',
    headers: headers(requestId),
    body: JSON.stringify({ status, reason }),
  })
}
