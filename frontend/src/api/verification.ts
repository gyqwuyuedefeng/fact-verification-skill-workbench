import type { VerificationClaim, VerificationTask } from '../types/verification'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Error(error?.message ?? `请求失败（${response.status}）`)
  }
  const body = await response.text()
  return body ? (JSON.parse(body) as T) : (undefined as T)
}

export function createTask(requestId: string): Promise<VerificationTask> {
  return request('/api/tasks', {
    method: 'POST',
    headers: { 'Idempotency-Key': requestId },
  })
}

export function uploadMaterial(
  taskId: string,
  requestId: string,
  file: File | null,
  message: string,
): Promise<VerificationTask> {
  const body = new FormData()
  if (file) body.append('file', file)
  if (message.trim()) body.append('message', message.trim())
  return request(`/api/tasks/${taskId}/materials`, {
    method: 'POST',
    headers: { 'Idempotency-Key': requestId },
    body,
  })
}

export function startRun(
  taskId: string,
  requestId: string,
  executionMode: 'BASELINE' | 'STABLE',
): Promise<VerificationTask> {
  return request(`/api/tasks/${taskId}/runs`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': requestId,
    },
    body: JSON.stringify({ executionMode }),
  })
}

export function getPrimaryClaims(taskId: string): Promise<VerificationClaim[]> {
  return request(`/api/tasks/${taskId}/claims`)
}

export function getTask(taskId: string): Promise<VerificationTask> {
  return request(`/api/tasks/${taskId}`)
}

export function getRunClaims(runId: string): Promise<VerificationClaim[]> {
  return request(`/api/runs/${runId}/claims`)
}

export function reviewShadowRun(
  runId: string,
  requestId: string,
  status: 'PASS' | 'FAIL',
  reason: string,
): Promise<void> {
  return request(`/api/runs/${runId}/review`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestId },
    body: JSON.stringify({ status, reason }),
  })
}

/** 这里是浏览器业务事件流；Agent 到 MCP 仍只走独立 `/mcp` Streamable HTTP。 */
export function openRunEventStream(
  runId: string,
  onEvent: (type: string, data: Record<string, unknown>) => void,
): EventSource {
  const source = new EventSource(`/api/runs/${runId}/events`)
  const eventTypes = [
    'RUN_CREATED',
    'TEXT_DELTA',
    'TOOL_STARTED',
    'TOOL_ENDED',
    'AGENT_RESULT',
    'AGENT_ENDED',
    'RUN_COMPLETED',
    'RUN_FAILED',
    'RUN_STATUS',
  ]
  for (const type of eventTypes) {
    source.addEventListener(type, (event) => {
      const message = event as MessageEvent<string>
      onEvent(type, JSON.parse(message.data) as Record<string, unknown>)
      if (type === 'RUN_COMPLETED' || type === 'RUN_FAILED') source.close()
    })
  }
  return source
}
