import type {
  SkillDraftContent,
  SkillReference,
  SkillVersion,
  SkillVersionContent,
  VersionCard,
  VersionComparison,
} from '../types/skill'

const root = '/api/skills/company-material-fact-check'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    const error = (await response.json().catch(() => null)) as { message?: string } | null
    throw new Error(error?.message ?? `请求失败（${response.status}）`)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export function getSkillDraft(draftId: string): Promise<SkillDraftContent> {
  return request(`${root}/drafts/${draftId}`)
}

/** 读取 DRAFT 或冻结历史版本的完整正文，编辑能力以服务端 editable 为准。 */
export function getSkillVersionContent(versionId: string): Promise<SkillVersionContent> {
  return request(`${root}/versions/${versionId}/content`)
}

export function createSkillDraft(
  requestId: string,
  parentVersionId: string | null,
  changeSummary: string,
): Promise<SkillVersion> {
  return request(`${root}/drafts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestId },
    body: JSON.stringify({ parentVersionId, changeSummary }),
  })
}

export function updateSkillDraft(
  requestId: string,
  draftId: string,
  skillMarkdown: string,
  references: SkillReference[],
  changeSummary: string,
): Promise<SkillVersion> {
  return request(`${root}/drafts/${draftId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestId },
    body: JSON.stringify({ skillMarkdown, references, changeSummary }),
  })
}

export function freezeSkillDraft(requestId: string, draftId: string): Promise<SkillVersion> {
  return request(`${root}/drafts/${draftId}/freeze`, {
    method: 'POST',
    headers: { 'Idempotency-Key': requestId },
  })
}

/** 删除未进入版本谱系的 DRAFT；后端会再次保护冻结版本和已有子版本的草稿。 */
export function deleteSkillDraft(requestId: string, draftId: string): Promise<void> {
  return request(`${root}/drafts/${draftId}`, {
    method: 'DELETE',
    headers: { 'Idempotency-Key': requestId },
  })
}

export function listSkillVersions(): Promise<SkillVersion[]> {
  return request(`${root}/versions`)
}

export function getVersionCard(versionId: string): Promise<VersionCard> {
  return request(`${root}/versions/${versionId}/card`)
}

export function compareSkillVersions(
  targetVersionId: string,
  baseVersionId: string,
): Promise<VersionComparison> {
  return request(`${root}/versions/${targetVersionId}/comparison`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ baseVersionId }),
  })
}
