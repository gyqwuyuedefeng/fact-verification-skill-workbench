import { defineStore } from 'pinia'

import {
  compareSkillVersions,
  createSkillDraft,
  deleteSkillDraft,
  freezeSkillDraft,
  getSkillVersionContent,
  getSkillVersionComparison,
  getVersionCard,
  listSkillVersions,
  updateSkillDraft,
} from '../api/skill'
import type {
  SkillReference,
  SkillVersion,
  SkillVersionContent,
  VersionCard,
  VersionComparison,
} from '../types/skill'

const requestId = (prefix: string) => `${prefix}-${crypto.randomUUID()}`

function matchesComparison(
  comparison: VersionComparison,
  targetVersionId: string,
  baseVersionId: string,
): boolean {
  return comparison.targetVersionId === targetVersionId && comparison.baseVersionId === baseVersionId
}

export const useSkillStore = defineStore('skill', {
  state: () => ({
    versions: [] as SkillVersion[],
    currentDraft: null as SkillVersion | null,
    selectedContent: null as SkillVersionContent | null,
    selectedVersionId: null as string | null,
    card: null as VersionCard | null,
    comparison: null as VersionComparison | null,
    comparisonRequestGeneration: 0,
    busy: false,
    error: null as string | null,
  }),
  actions: {
    async load() {
      this.versions = await listSkillVersions()
    },
    async createDraft(parentVersionId: string | null = null, changeSummary = '创建评测候选版') {
      this.busy = true
      try {
        this.currentDraft = await createSkillDraft(
          requestId('draft'),
          parentVersionId,
          changeSummary,
        )
        this.selectedVersionId = this.currentDraft.id
        this.selectedContent = await getSkillVersionContent(this.currentDraft.id)
        await this.load()
      } finally {
        this.busy = false
      }
    },
    async saveDraft(skillMarkdown: string, references: SkillReference[], changeSummary: string) {
      if (!this.currentDraft) return
      this.currentDraft = await updateSkillDraft(
        requestId('update'),
        this.currentDraft.id,
        skillMarkdown,
        references,
        changeSummary,
      )
      this.selectedContent = await getSkillVersionContent(this.currentDraft.id)
      await this.load()
    },
    async freezeCurrent() {
      if (!this.currentDraft) return
      const frozen = await freezeSkillDraft(requestId('freeze'), this.currentDraft.id)
      this.currentDraft = null
      this.selectedVersionId = frozen.id
      this.selectedContent = await getSkillVersionContent(frozen.id)
      this.card = await getVersionCard(frozen.id)
      await this.load()
    },
    async selectVersion(versionId: string) {
      const version = this.versions.find((item) => item.id === versionId)
      this.selectedVersionId = versionId
      this.selectedContent = await getSkillVersionContent(versionId)
      this.currentDraft = this.selectedContent.editable && version?.status === 'DRAFT' ? version : null
      this.card = version?.status === 'DRAFT' ? null : await getVersionCard(versionId)
    },
    /** 二次确认由页面负责；此处只提交删除并清理已经失效的本地选择。 */
    async deleteCurrentDraft() {
      if (!this.currentDraft) return
      this.busy = true
      this.error = null
      try {
        await deleteSkillDraft(requestId('delete-draft'), this.currentDraft.id)
        this.currentDraft = null
        this.selectedContent = null
        this.selectedVersionId = null
        this.card = null
        await this.load()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '删除 DRAFT 失败'
        throw error
      } finally {
        this.busy = false
      }
    },
    /** 版本对变化时使正在进行的请求失效，并立即隐藏上一版本对的摘要。 */
    clearComparison() {
      this.comparisonRequestGeneration += 1
      this.comparison = null
      this.error = null
      this.busy = false
    },
    /** 选择版本对时仅恢复服务端保存的摘要，不调用模型。 */
    async loadComparison(targetVersionId: string, baseVersionId: string) {
      const generation = ++this.comparisonRequestGeneration
      this.busy = true
      this.error = null
      this.comparison = null
      try {
        const comparison = await getSkillVersionComparison(targetVersionId, baseVersionId)
        if (generation !== this.comparisonRequestGeneration) return
        if (!matchesComparison(comparison, targetVersionId, baseVersionId)) {
          this.error = '读取的升级说明与当前版本对不匹配'
          return
        }
        this.comparison = comparison
        if (comparison.errorCode) {
          this.error = `升级说明生成失败（${comparison.errorCode}）`
        }
      } catch (error) {
        if (generation === this.comparisonRequestGeneration) {
          this.error = error instanceof Error ? error.message : '读取升级说明失败'
        }
      } finally {
        if (generation === this.comparisonRequestGeneration) this.busy = false
      }
    },
    /** 管理员明确点击后才生成；失败时保留页面上已恢复的旧摘要。 */
    async generateComparison(targetVersionId: string, baseVersionId: string) {
      const generation = ++this.comparisonRequestGeneration
      this.busy = true
      this.error = null
      try {
        const comparison = await compareSkillVersions(targetVersionId, baseVersionId)
        if (generation !== this.comparisonRequestGeneration) return
        if (!matchesComparison(comparison, targetVersionId, baseVersionId)) {
          this.comparison = null
          this.error = '生成的升级说明与当前版本对不匹配'
          return
        }
        this.comparison = comparison
        if (comparison.errorCode) {
          this.error = `升级说明生成失败（${comparison.errorCode}）`
        }
      } catch (error) {
        if (generation === this.comparisonRequestGeneration) {
          this.error = error instanceof Error ? error.message : '生成升级说明失败'
        }
      } finally {
        if (generation === this.comparisonRequestGeneration) this.busy = false
      }
    },
  },
})
