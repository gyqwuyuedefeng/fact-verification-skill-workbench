import type { DemoState, SkillPreset } from '../types/demo'

const root = '/api/admin/demo-state'

function requestId(prefix: string): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return `${prefix}-${globalThis.crypto.randomUUID()}`
  }
  if (typeof globalThis.crypto?.getRandomValues !== 'function') {
    throw new Error('当前浏览器不支持安全请求标识，无法执行演示数据操作')
  }
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const uuid = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${prefix}-${uuid.slice(0, 8)}-${uuid.slice(8, 12)}-${uuid.slice(12, 16)}-${uuid.slice(16, 20)}-${uuid.slice(20)}`
}

function jsonHeaders(idempotencyKey: string): HeadersInit {
  return { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey }
}

/**
 * Fetch 的 Header 值只能包含单字节字符，因此不能直接写入中文确认短语。
 * 将 UTF-8 原始字节逐个映射为 ISO-8859-1 字符，后端再按同一固定规则严格还原。
 */
function utf8ConfirmationHeader(value: string): string {
  return Array.from(new TextEncoder().encode(value), (byte) => String.fromCharCode(byte)).join('')
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Error(error?.message ?? `请求失败（${response.status}）`)
  }
  return (await response.json()) as T
}

export function getDemoState(): Promise<DemoState> {
  return request(`${root}/status`)
}

export function resetDemoState(confirmationPhrase: string): Promise<DemoState> {
  return request(`${root}/reset`, {
    method: 'POST',
    headers: jsonHeaders(requestId('demo-reset')),
    body: JSON.stringify({ confirmationPhrase }),
  })
}

export function importSnapshot(file: File): Promise<DemoState> {
  return request(`${root}/import`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/zip',
      'Idempotency-Key': requestId('demo-import'),
      'X-Confirmation-Phrase': utf8ConfirmationHeader('导入快照'),
    },
    body: file,
  })
}

export function importBuiltinDemoState(): Promise<DemoState> {
  return request(`${root}/import-builtin`, {
    method: 'POST',
    headers: {
      'Idempotency-Key': requestId('demo-import-builtin'),
      'X-Confirmation-Phrase': utf8ConfirmationHeader('导入内置演示数据'),
    },
  })
}

export function getSkillPresets(): Promise<SkillPreset[]> {
  return request(`${root}/skill-presets`)
}

/** 导出使用原始 Response 读取 ZIP，避免通用 JSON helper 误解析二进制响应。 */
export async function exportDemoSnapshot(): Promise<void> {
  const response = await fetch(`${root}/export`)
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Error(error?.message ?? `导出失败（${response.status}）`)
  }
  const objectUrl = URL.createObjectURL(await response.blob())
  try {
    const anchor = document.createElement('a')
    anchor.href = objectUrl
    anchor.download = 'workbench-state-snapshot.zip'
    anchor.style.display = 'none'
    document.body.append(anchor)
    anchor.click()
    anchor.remove()
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}
