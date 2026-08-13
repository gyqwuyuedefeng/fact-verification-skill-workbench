import { defineStore } from 'pinia'

import {
  exportDemoSnapshot,
  getDemoState,
  getSkillPresets,
  importBuiltinDemoState,
  importSnapshot,
  resetDemoState,
} from '../api/demo'
import {
  DEMO_DIRECTORY_KEYS,
  DEMO_TABLE_KEYS,
  type DemoState,
  type SkillPreset,
} from '../types/demo'

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

/** 只接受精确键集合，避免空对象、缺键或未知键被 every() 误判为可安全导入。 */
function hasExactKeys(value: unknown, expectedKeys: readonly string[]): value is Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const keys = Object.keys(value)
  return keys.length === expectedKeys.length
    && expectedKeys.every((key) => Object.prototype.hasOwnProperty.call(value, key))
}

/** 来自 HTTP 的 JSON 在运行时仍需校验，导入快照必须对任何异常状态失败关闭。 */
function isDemoStateContractValid(demoState: DemoState): boolean {
  const tableCounts: unknown = demoState.tableCounts
  const storageEmpty: unknown = demoState.storageEmpty
  return hasExactKeys(tableCounts, DEMO_TABLE_KEYS)
    && hasExactKeys(storageEmpty, DEMO_DIRECTORY_KEYS)
    && DEMO_TABLE_KEYS.every((key) => {
      const count = tableCounts[key]
      return typeof count === 'number' && Number.isFinite(count) && Number.isInteger(count) && count >= 0
    })
    && DEMO_DIRECTORY_KEYS.every((key) => typeof storageEmpty[key] === 'boolean')
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
    /** 状态合同异常时页面仍可提示，但所有自定义导入必须失败关闭。 */
    isStateContractValid(state): boolean {
      return state.state !== null && isDemoStateContractValid(state.state)
    },
    /** 自定义快照只能在七表与三个受管目录均为空时导入。 */
    isBlank(state): boolean {
      if (!state.state || !isDemoStateContractValid(state.state)) return false
      return DEMO_TABLE_KEYS.every((key) => state.state!.tableCounts[key] === 0)
        && DEMO_DIRECTORY_KEYS.every((key) => state.state!.storageEmpty[key] === true)
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
