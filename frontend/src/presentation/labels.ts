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

/** 为状态增加中文业务名称，同时保留接口原始英文值以便追溯。 */
export function statusLabel(value: string | null | undefined): string {
  const rawValue = value ?? 'UNKNOWN'
  return `${statusLabels[rawValue] ?? '未知状态'}（${rawValue}）`
}

/** 指标名称始终同时展示前端中文文案和后端字段名。 */
export function metricLabel(value: string): string {
  return `${metricLabels[value] ?? '未知指标'}（${value}）`
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
