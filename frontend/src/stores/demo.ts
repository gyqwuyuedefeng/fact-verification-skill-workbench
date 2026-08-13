import { defineStore } from 'pinia'

import {
  exportDemoSnapshot,
  getDemoState,
  getSkillPresets,
  importBuiltinDemoState,
  importSnapshot,
  resetDemoState,
} from '../api/demo'
import type { DemoState, SkillPreset } from '../types/demo'

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

export const useDemoStore = defineStore('demo', {
  state: () => ({
    state: null as DemoState | null,
    skillPresets: [] as SkillPreset[],
    busy: false,
    presetsBusy: false,
    error: null as string | null,
  }),
  getters: {
    /** 自定义快照只能在七表与三个受管目录均为空时导入。 */
    isBlank(state): boolean {
      if (!state.state) return false
      return Object.values(state.state.tableCounts).every((count) => count === 0)
        && Object.values(state.state.storageEmpty).every(Boolean)
    },
  },
  actions: {
    async load() {
      this.busy = true
      this.error = null
      try {
        this.state = await getDemoState()
      } catch (error) {
        this.error = errorMessage(error, '读取演示数据状态失败')
      } finally {
        this.busy = false
      }
    },
    async reset(confirmationPhrase: string) {
      this.busy = true
      this.error = null
      try {
        await resetDemoState(confirmationPhrase)
        this.state = await getDemoState()
      } catch (error) {
        this.error = errorMessage(error, '清空演示数据失败')
      } finally {
        this.busy = false
      }
    },
    async importSnapshot(file: File) {
      this.busy = true
      this.error = null
      try {
        await importSnapshot(file)
        this.state = await getDemoState()
      } catch (error) {
        this.error = errorMessage(error, '导入快照失败')
      } finally {
        this.busy = false
      }
    },
    async importBuiltin() {
      this.busy = true
      this.error = null
      try {
        await importBuiltinDemoState()
        this.state = await getDemoState()
      } catch (error) {
        this.error = errorMessage(error, '导入内置演示数据失败')
      } finally {
        this.busy = false
      }
    },
    async exportSnapshot() {
      this.busy = true
      this.error = null
      try {
        await exportDemoSnapshot()
      } catch (error) {
        this.error = errorMessage(error, '导出演示快照失败')
      } finally {
        this.busy = false
      }
    },
    async loadSkillPresets() {
      if (this.skillPresets.length || this.presetsBusy) return
      this.presetsBusy = true
      this.error = null
      try {
        this.skillPresets = await getSkillPresets()
      } catch (error) {
        this.error = errorMessage(error, '读取预置 Skill 失败')
      } finally {
        this.presetsBusy = false
      }
    },
  },
})
