export type VerificationStatus =
  'UPLOADED' | 'PARSING' | 'READY' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'

export interface VerificationTask {
  id: string
  inputType: 'TEXT' | 'FILE' | 'COMBINED' | null
  messagePresent: boolean
  executionMode: 'BASELINE' | 'STABLE' | null
  fileName: string | null
  fileHash: string | null
  documentSnapshotHash: string | null
  status: VerificationStatus
  primaryRunId: string | null
  errorCode: string | null
  createdAt: string
}

export type ClaimStatus = 'VERIFIED' | 'CONFLICT' | 'INSUFFICIENT'

export interface VerificationClaim {
  id: string
  claimText: string
  materialLocator: Record<string, unknown>
  normalizedClaim: Record<string, unknown>
  subject: Record<string, unknown> | null
  status: ClaimStatus
  riskFlags: string[]
  evidence: Array<Record<string, unknown>>
  explanation: string
  requiresHumanIntervention: boolean
}

export interface RunBusinessEvent {
  id?: string
  type: string
  data: Record<string, unknown>
}

export interface VerificationConversation {
  id: string
  message: string
  fileName: string | null
  executionMode: 'BASELINE' | 'STABLE'
  task: VerificationTask | null
  claims: VerificationClaim[]
  events: RunBusinessEvent[]
  error: string | null
}
