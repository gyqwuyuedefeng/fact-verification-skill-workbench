import type { SkillReference } from './skill'

/** 后端 DemoStateView 固定返回的七张业务表，页面按此顺序展示并校验合同。 */
export const DEMO_TABLE_KEYS = [
  'claim',
  'verification_run',
  'verification_task',
  'evidence_snapshot',
  'release_binding',
  'skill_version',
  'evaluation_run',
] as const

/** 后端 DemoStateView 固定返回的三个受管目录，页面不依赖对象遍历顺序。 */
export const DEMO_DIRECTORY_KEYS = ['uploads', 'skill-snapshots', 'skill-runtime'] as const

export type DemoTableKey = (typeof DEMO_TABLE_KEYS)[number]
export type DemoDirectoryKey = (typeof DEMO_DIRECTORY_KEYS)[number]

/** 演示管理接口返回的脱敏状态；不包含实际目录路径或附件名称。 */
export interface DemoState {
  tableCounts: Record<string, number>
  storageEmpty: Record<string, boolean>
}

/** 固定三阶段 Skill 预置的完整编辑器填充值。 */
export interface SkillPreset {
  id: string
  label: string
  skillName: string
  skillMarkdown: string
  references: SkillReference[]
}
