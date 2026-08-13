<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'

import { skillVersionLabel, statusLabel } from '../presentation/labels'
import { useSkillStore } from '../stores/skill'
import type { SkillReference } from '../types/skill'

const store = useSkillStore()
const changeSummary = ref('修复主体歧义或已知失败样本')
const skillMarkdown = ref('')
const referencesJson = ref('[]')
const targetVersionId = ref('')
const baseVersionId = ref('')

onMounted(() => {
  store.load().catch(() => undefined)
})

function loadEditorFromStore() {
  const content = store.selectedContent
  if (!content) return
  skillMarkdown.value = content.skillMarkdown
  referencesJson.value = JSON.stringify(content.references, null, 2)
  changeSummary.value = content.changeSummary
}

async function createDraft() {
  await store.createDraft(store.selectedVersionId, changeSummary.value)
  loadEditorFromStore()
}

async function save() {
  if (!store.currentDraft || !skillMarkdown.value.trim()) return
  let references: SkillReference[] = []
  try {
    references = JSON.parse(referencesJson.value) as SkillReference[]
  } catch {
    store.error = 'references 必须是合法 JSON 数组'
    return
  }
  await store.saveDraft(skillMarkdown.value, references, changeSummary.value)
}

async function selectVersion(versionId: string) {
  targetVersionId.value = versionId
  await store.selectVersion(versionId)
  loadEditorFromStore()
}

async function deleteDraft() {
  if (!store.currentDraft) return
  const confirmed = globalThis.confirm(`确认删除草稿（DRAFT）「${store.currentDraft.changeSummary}」？删除后无法恢复。`)
  if (!confirmed) return
  await store.deleteCurrentDraft()
  skillMarkdown.value = ''
  referencesJson.value = '[]'
  changeSummary.value = '修复主体歧义或已知失败样本'
}

async function generateChangeSummary() {
  if (!targetVersionId.value || !baseVersionId.value) return
  await store.generateComparison(targetVersionId.value, baseVersionId.value)
}

/** 版本对变化只读取已保存结果；模型调用由管理员按钮显式触发。 */
watch([targetVersionId, baseVersionId], ([targetVersionId, baseVersionId]) => {
  store.clearComparison()
  if (!targetVersionId || !baseVersionId || targetVersionId === baseVersionId) return
  void store.loadComparison(targetVersionId, baseVersionId)
})
</script>

<template>
  <section>
    <p class="eyebrow">ADMIN / SKILL VERSION</p>
    <div class="page-heading">
      <div>
        <h1>Skill 版本实验室</h1>
        <p class="page-lead">点击任意版本查看完整内容：草稿（DRAFT）可编辑可删除，冻结版本永久只读。</p>
      </div>
      <button
        class="secondary-action"
        data-test="create-draft"
        :disabled="store.busy || Boolean(store.currentDraft) || (Boolean(store.versions.length) && !store.selectedVersionId)"
        @click="createDraft"
      >
        {{ store.selectedVersionId ? '从已选版本克隆草稿（DRAFT）' : store.versions.length ? '请先选择一个版本' : '新建初始草稿（DRAFT）' }}
      </button>
    </div>

    <div class="skill-layout">
      <section class="panel skill-editor">
        <div class="panel-heading">
          <span class="step-index">01</span>
          <div>
            <strong>{{ store.currentDraft ? '草稿（DRAFT）编辑' : store.selectedContent ? '版本内容（只读）' : '选择版本查看内容' }}</strong>
            <small>Skill 主文件（SKILL.md）+ 参考资料 + 变更说明</small>
          </div>
        </div>
        <label class="field-label">变更说明</label>
        <input v-model="changeSummary" class="text-input" maxlength="2000" :readonly="!store.currentDraft" />
        <label class="field-label">Skill 主文件（SKILL.md）</label>
        <textarea
          v-model="skillMarkdown"
          class="code-editor"
          rows="13"
          placeholder="创建草稿（DRAFT）后，在这里粘贴或调整 SKILL.md"
          :readonly="!store.currentDraft"
        ></textarea>
        <label class="field-label">参考资料（references JSON）</label>
        <textarea v-model="referencesJson" class="code-editor small" rows="4" :readonly="!store.currentDraft"></textarea>
        <div v-if="store.currentDraft" class="editor-actions">
          <button class="secondary-action" :disabled="!store.currentDraft" @click="save">
            保存草稿（DRAFT）
          </button>
          <button
            class="secondary-action danger-action"
            data-test="delete-draft"
            :disabled="store.busy"
            @click="deleteDraft"
          >
            删除草稿（DRAFT）
          </button>
          <button
            class="primary-action compact"
            data-test="freeze-draft"
            :disabled="!store.currentDraft"
            @click="store.freezeCurrent"
          >
            冻结为候选版（CANDIDATE）
          </button>
        </div>
        <p v-if="store.error" class="error-message">{{ store.error }}</p>
      </section>

      <section class="panel version-list-panel">
        <div class="panel-heading">
          <span class="step-index">02</span>
          <div><strong>版本历史</strong><small>草稿 / 候选版 / 稳定版 / 已归档</small></div>
        </div>
        <div v-if="!store.versions.length" class="compact-empty">暂无版本。</div>
        <button
          v-for="version in store.versions"
          :key="version.id"
          class="version-row"
          @click="selectVersion(version.id)"
        >
          <span class="version-status" :class="version.status.toLowerCase()">{{ statusLabel(version.status) }}</span>
          <div>
            <strong>{{ version.version ?? '未冻结草稿' }}</strong><small>{{ version.changeSummary }}</small>
          </div>
          <code>{{ version.contentHash?.slice(0, 12) ?? '可编辑' }}</code>
        </button>
      </section>
    </div>

    <section class="results-section version-card-panel">
      <div class="results-heading">
        <div><span class="step-index">03</span><strong>版本卡</strong></div>
        <span class="gate-chip" :class="store.card?.gateStatus.toLowerCase()">门禁 {{ statusLabel(store.card?.gateStatus) }}</span>
      </div>
      <div v-if="!store.card" class="empty-results">选择一个冻结版本查看版本卡。</div>
      <div v-else class="version-card-grid">
        <div>
          <small>版本号（VERSION）</small><strong>{{ store.card.version }}</strong>
        </div>
        <div>
          <small>父版本（PARENT）</small><strong>{{ store.card.parentVersion ?? '根版本' }}</strong>
        </div>
        <div>
          <small>内容哈希（CONTENT HASH）</small><code>{{ store.card.contentHash }}</code>
        </div>
        <div>
          <small>改动说明（CHANGE）</small><strong>{{ store.card.changeSummary }}</strong>
        </div>
        <div class="card-wide">
          <small>注册（REGISTER）</small>
          <strong v-if="store.card.gateStatus === 'PASS'">离线门禁通过，可以注册候选版（CANDIDATE）</strong>
          <strong v-else>完成同条件评测后才能注册</strong>
        </div>
      </div>
    </section>

    <section class="results-section version-card-panel" data-test="version-comparison">
      <div class="results-heading">
        <div><span class="step-index">04</span><strong>版本升级说明</strong></div>
        <small>手动触发 · AI 说明不参与发布门禁</small>
      </div>
      <div class="comparison-controls">
        <label>
          <span>目标版本</span>
          <select v-model="targetVersionId" class="text-input" data-test="target-version">
            <option value="">请选择</option>
            <option v-for="version in store.versions.filter((item) => item.status !== 'DRAFT')" :key="version.id" :value="version.id">{{ skillVersionLabel(version) }}</option>
          </select>
        </label>
        <label>
          <span>基准版本</span>
          <select v-model="baseVersionId" class="text-input" data-test="base-version">
            <option value="">请选择上一版或当前 Stable</option>
            <option v-for="version in store.versions.filter((item) => item.status !== 'DRAFT')" :key="version.id" :value="version.id">{{ skillVersionLabel(version) }}</option>
          </select>
        </label>
        <button class="primary-action compact" data-test="generate-change-summary" :disabled="!targetVersionId || !baseVersionId || targetVersionId === baseVersionId || store.busy" @click="generateChangeSummary">生成升级说明</button>
      </div>
      <div v-if="store.comparison" class="comparison-result-grid">
        <article class="panel">
          <div class="panel-heading"><div><strong>确定性原文差异</strong><small>始终可用，可直接人工核对</small></div></div>
          <pre data-test="deterministic-diff" class="diff-view">{{ store.comparison.deterministicDiff }}</pre>
        </article>
        <article class="panel">
          <div class="panel-heading"><div><strong>AI 升级摘要</strong><small>{{ store.comparison.advisory }}</small></div></div>
          <template v-if="store.comparison.generatedSummary">
            <h3>{{ store.comparison.generatedSummary.headline }}</h3>
            <strong>主要改动</strong>
            <ul><li v-for="item in store.comparison.generatedSummary.changes" :key="item">{{ item }}</li></ul>
            <strong>审核关注</strong>
            <ul><li v-for="item in store.comparison.generatedSummary.reviewRisks" :key="item">{{ item }}</li></ul>
          </template>
          <div v-else class="compact-empty">模型说明暂不可用，请直接审核左侧原文差异。</div>
        </article>
      </div>
      <div v-else class="empty-results">选择目标版本与基准版本，再由管理员手动生成说明。</div>
      <p v-if="store.error" class="error-message">{{ store.error }}</p>
    </section>
  </section>
</template>
