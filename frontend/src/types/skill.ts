export interface SkillVersion {
  id: string
  skillKey: string
  version: string | null
  parentVersionId: string | null
  status: 'DRAFT' | 'CANDIDATE' | 'STABLE' | 'ARCHIVED'
  contentHash: string | null
  changeSummary: string
  createdAt: string
  frozenAt: string | null
}

export interface SkillReference {
  path: string
  content: string
}

export interface SkillDraftContent {
  id: string
  parentVersionId: string | null
  skillMarkdown: string
  references: SkillReference[]
  changeSummary: string
}

/** 管理端统一版本正文；冻结状态可查看但 editable 始终为 false。 */
export interface SkillVersionContent extends SkillDraftContent {
  status: SkillVersion['status']
  editable: boolean
}

export interface VersionCard {
  skillKey: string
  version: string
  status: 'CANDIDATE' | 'STABLE' | 'ARCHIVED'
  parentVersion: string | null
  contentHash: string
  changeSummary: string
  evaluationRunId: string | null
  metrics: Record<string, unknown> | null
  gateStatus: 'PASS' | 'FAIL' | 'PENDING'
  knownFailures: string[]
}

export interface GeneratedChangeSummary {
  headline: string
  changes: string[]
  reviewRisks: string[]
}

/** 逐行差异是确定性事实；模型摘要仅帮助管理员快速阅读。 */
export interface VersionComparison {
  targetVersionId: string
  baseVersionId: string
  baseContentHash: string
  targetContentHash: string
  deterministicDiff: string
  summaryStatus: 'NOT_GENERATED' | 'COMPLETED' | 'UNAVAILABLE'
  modelId: string | null
  generatedAt: string | null
  persisted: boolean
  generatedSummary: GeneratedChangeSummary | null
  advisory: string
  errorCode: string | null
}
