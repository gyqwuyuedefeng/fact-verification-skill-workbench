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

export const useSkillStore = defineStore('skill', {
  state: () => ({
    versions: [] as SkillVersion[],
    currentDraft: null as SkillVersion | null,
    selectedContent: null as SkillVersionContent | null,
    selectedVersionId: null as string | null,
    card: null as VersionCard | null,
    comparison: null as VersionComparison | null,
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
    /** 选择版本对时仅恢复服务端保存的摘要，不调用模型。 */
    async loadComparison(targetVersionId: string, baseVersionId: string) {
      this.busy = true
      this.error = null
      try {
        this.comparison = await getSkillVersionComparison(targetVersionId, baseVersionId)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '读取升级说明失败'
      } finally {
        this.busy = false
      }
    },
    /** 管理员明确点击后才生成；失败时保留页面上已恢复的旧摘要。 */
    async generateComparison(targetVersionId: string, baseVersionId: string) {
      this.busy = true
      this.error = null
      try {
        this.comparison = await compareSkillVersions(targetVersionId, baseVersionId)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '生成升级说明失败'
      } finally {
        this.busy = false
      }
    },
  },
})
