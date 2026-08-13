import type { SkillReference } from './skill'

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
