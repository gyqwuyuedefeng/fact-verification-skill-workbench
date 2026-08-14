import type { SkillVersion } from '../types/skill'

const statusLabels: Record<string, string> = {
  PENDING: '待处理',
  RUNNING: '运行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  INTERRUPTED: '已中断',
  DRAFT: '草稿',
  CANDIDATE: '候选版',
  STABLE: '稳定版',
  ARCHIVED: '已归档',
  PASS: '通过',
  FAIL: '未通过',
  VERIFIED: '已核验',
  CONFLICT: '存在冲突',
  INSUFFICIENT: '证据不足',
  BASELINE: '基线',
}

const releaseActionLabels: Record<string, string> = {
  INITIALIZE: '初始化',
  REGISTER: '注册候选版',
  SHADOW_START: '开启影子',
  SHADOW_STOP: '停止影子',
  PROMOTE: '晋升稳定版',
  ROLLBACK: '回滚上一版',
}

const metricLabels: Record<string, string> = {
  accuracy: '准确率',
  completionRate: '任务完成率',
  stability: '稳定性',
  humanInterventionRate: '人工介入率',
}

const evaluationDatasetLabels: Record<string, string> = {
  'public-tech-live-smoke-v1': '现场快速评测（3 条）',
  'public-tech-2024-v3': '历史正式评测（30 条，仅查看）',
  'public-tech-2024-v4': '正式完整评测（30 条）',
}

/** 为状态增加中文业务名称，同时保留接口原始英文值以便追溯。 */
export function statusLabel(value: string | null | undefined): string {
  const rawValue = value ?? 'UNKNOWN'
  return `${statusLabels[rawValue] ?? '未知状态'}（${rawValue}）`
}

/** 指标名称始终同时展示前端中文文案和后端字段名。 */
export function metricLabel(value: string): string {
  return `${metricLabels[value] ?? '未知指标'}（${value}）`
}

/** 固定评测集使用业务名称与不可变版本号双重标识，避免现场只看到内部英文版本。 */
export function evaluationDatasetLabel(value: string): string {
  return `${evaluationDatasetLabels[value] ?? '未知评测集'} · ${value}`
}

/** 快速集只用于确认链路和观察指标，正式三十条才具有注册与发布资格。 */
export function evaluationDatasetReleaseHint(value: string): string {
  if (value === 'public-tech-2024-v4') {
    return '正式完整评测：完成且门禁通过后，可作为发布门禁，用于注册候选版和后续发布。'
  }
  if (value === 'public-tech-live-smoke-v1') {
    return '现场快速评测：真实调用模型、Agent、MCP 与 ES，但不可用于注册或发布。'
  }
  return '历史评测：保留原始结果用于审计，不再允许新建、注册或发布。'
}

/** 冻结版本选项保留状态、语义化版本和短 ID，避免仅靠中文名称选择错误版本。 */
export function skillVersionLabel(version: Pick<SkillVersion, 'id' | 'version' | 'status'>): string {
  return `${statusLabel(version.status)} · ${version.version ?? '无版本号'} · ${shortId(version.id)}`
}

/** 发布历史操作同时显示中文业务含义和服务端不可变操作码。 */
export function releaseActionLabel(value: string): string {
  return `${releaseActionLabels[value] ?? '未知操作'}（${value}）`
}

/** 基线是固定评测变体，其他变体保留服务端版本标识便于追溯。 */
export function variantLabel(value: string): string {
  return value === 'BASELINE' ? statusLabel(value) : value
}

/** 在历史列表中压缩长标识，同时保留可辨识的固定前缀。 */
export function shortId(value: string): string {
  return value.slice(0, 8)
}
