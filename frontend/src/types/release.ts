export interface ReleaseState {
  revision: number
  stableVersionId: string | null
  candidateVersionId: string | null
  previousStableVersionId: string | null
  shadowEnabled: boolean
  action: 'INITIALIZE' | 'REGISTER' | 'SHADOW_START' | 'SHADOW_STOP' | 'PROMOTE' | 'ROLLBACK'
  reason: string
  createdAt: string
}

export interface ShadowRunItem {
  taskId: string
  fileName: string
  companyNames: string
  primaryRunId: string | null
  shadowRunId: string
  stableVersionId: string | null
  candidateVersionId: string
  primaryStatus: string
  shadowStatus: string
  reviewStatus: 'PENDING' | 'PASS' | 'FAIL'
  agreementCount: number
  differenceCount: number
  createdAt: string
}

export interface ShadowHistory {
  items: ShadowRunItem[]
  summary: Record<string, number>
  accuracyAvailable: false
}
