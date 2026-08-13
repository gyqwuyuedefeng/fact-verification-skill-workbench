<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { useDemoStore } from '../stores/demo'
import {
  DEMO_DIRECTORY_KEYS,
  DEMO_TABLE_KEYS,
  type DemoDirectoryKey,
  type DemoTableKey,
} from '../types/demo'

const store = useDemoStore()
const selectedFile = ref<File | null>(null)

const tableLabels: Record<DemoTableKey, string> = {
  claim: '主张',
  verification_run: '核验运行',
  verification_task: '核验任务',
  evidence_snapshot: '证据快照',
  release_binding: '发布绑定',
  skill_version: 'Skill 版本',
  evaluation_run: '评测运行',
}

const directoryLabels: Record<DemoDirectoryKey, string> = {
  uploads: '上传材料目录',
  'skill-snapshots': 'Skill 快照目录',
  'skill-runtime': 'Skill 运行目录',
}

const demoSteps = [
  '清空演示数据',
  '创建首个 Skill',
  '编辑并保存草稿（DRAFT）',
  '冻结为候选版（CANDIDATE）',
  '执行同条件评测',
  '查看门禁结果',
  '注册候选版',
  '开启影子运行',
  '观察影子结果',
  '晋升 Stable 或回滚',
]

onMounted(() => {
  void store.load()
})

function selectSnapshot(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  selectedFile.value = file?.name.toLowerCase().endsWith('.zip') ? file : null
  if (file && !selectedFile.value) {
    store.error = '仅支持 .zip 格式的自定义演示快照'
  }
}

/** 即使服务端 JSON 缺失顶层映射，管理页也只展示占位，不在合同异常提示前解引用失败。 */
function tableCountValue(table: DemoTableKey): number | '—' {
  const tableCounts: unknown = store.state?.tableCounts
  if (typeof tableCounts !== 'object' || tableCounts === null || Array.isArray(tableCounts)) return '—'
  const value = (tableCounts as Record<string, unknown>)[table]
  return typeof value === 'number' ? value : '—'
}

/** 目录值不是严格 boolean 时以“未知”显示，且 store.isBlank 已保持导入失败关闭。 */
function directoryEmptyValue(directory: DemoDirectoryKey): boolean | null {
  const storageEmpty: unknown = store.state?.storageEmpty
  if (typeof storageEmpty !== 'object' || storageEmpty === null || Array.isArray(storageEmpty)) return null
  const value = (storageEmpty as Record<string, unknown>)[directory]
  return typeof value === 'boolean' ? value : null
}

async function reset() {
  const phrase = globalThis.prompt('此操作会清空七张比赛数据表和三个运行目录。请输入“清空全部比赛数据”确认：')
  if (phrase === null) return
  if (phrase !== '清空全部比赛数据') {
    store.error = '确认短语必须精确匹配“清空全部比赛数据”'
    return
  }
  await store.reset(phrase)
}

async function importCustomSnapshot() {
  if (!selectedFile.value || !store.isBlank) return
  await store.importSnapshot(selectedFile.value)
  if (!store.error) selectedFile.value = null
}

async function importBuiltin() {
  if (!globalThis.confirm('将导入固定脱敏内置数据，用于查看完整结果；它不是本次现场重新生成。确认继续？')) return
  await store.importBuiltin()
}

async function exportSnapshot() {
  if (!globalThis.confirm('导出的快照包含原始企业附件，可能涉及敏感数据。确认下载并妥善保管？')) return
  await store.exportSnapshot()
}
</script>

<template>
  <section>
    <p class="eyebrow">ADMIN / DEMO DATA</p>
    <div class="page-heading">
      <div>
        <h1>演示数据管理</h1>
        <p class="page-lead">仅用于演示管理员准备比赛数据；普通事实核验入口不会展示候选版本或影子运行信息。</p>
      </div>
      <button class="secondary-action" :disabled="store.busy" @click="store.load">
        {{ store.busy ? '正在刷新…' : '刷新状态' }}
      </button>
    </div>

    <div class="demo-path-grid">
      <section class="panel">
        <div class="panel-heading">
          <span class="step-index">01</span>
          <div><strong>从零现场演示（10 步）</strong><small>适合完整讲解数据如何逐步形成</small></div>
        </div>
        <ol class="demo-step-list">
          <li v-for="(step, index) in demoSteps" :key="step"><span>{{ index + 1 }}</span>{{ step }}</li>
        </ol>
        <button class="danger-action secondary-action" data-test="reset-demo-state" :disabled="store.busy" @click="reset">
          {{ store.busy ? '正在清空…' : '清空并从第 1 步开始' }}
        </button>
        <small class="destructive-hint">需精确输入“清空全部比赛数据”；取消或短语不匹配不会发送请求。</small>
      </section>

      <section class="panel">
        <div class="panel-heading">
          <span class="step-index">02</span>
          <div><strong>快速导入内置状态查看完整结果</strong><small>适合快速查看完整评测、门禁和发布链路</small></div>
        </div>
        <p class="notice-copy">内置导入使用固定脱敏数据，不是本次现场重新生成。</p>
        <button class="primary-action" data-test="import-builtin" :disabled="store.busy || !store.isBlank" @click="importBuiltin">
          {{ store.busy ? '正在导入…' : '导入固定脱敏内置状态' }}
        </button>
        <small v-if="!store.isBlank" class="destructive-hint">当前状态非空或状态合同无效，请先清空并刷新状态。</small>
      </section>
    </div>

    <section class="results-section" data-test="demo-state-status">
      <div class="results-heading"><div><span class="step-index">03</span><strong>当前演示数据状态</strong></div></div>
      <div v-if="!store.state && !store.error" class="compact-empty">正在读取七表与三个目录状态…</div>
      <div v-else-if="store.state" class="demo-state-grid">
        <article><strong>七张业务表</strong><dl><template v-for="table in DEMO_TABLE_KEYS" :key="table"><dt>{{ tableLabels[table] }}（{{ table }}）</dt><dd>{{ tableCountValue(table) }}</dd></template></dl></article>
        <article><strong>三个受管目录</strong><dl><template v-for="directory in DEMO_DIRECTORY_KEYS" :key="directory"><dt>{{ directoryLabels[directory] }}（{{ directory }}）</dt><dd :class="directoryEmptyValue(directory) === true ? 'state-ok' : 'state-alert'">{{ directoryEmptyValue(directory) === true ? '为空' : directoryEmptyValue(directory) === false ? '含运行数据' : '未知' }}</dd></template></dl></article>
      </div>
      <p v-if="store.state && !store.isStateContractValid" class="error-message">状态合同异常/无法安全导入自定义快照，请刷新后确认七表和三个目录状态。</p>
      <p v-if="store.error" class="error-message">{{ store.error }}</p>
    </section>

    <section class="demo-transfer-grid">
      <section class="results-section">
        <div class="results-heading"><div><span class="step-index">04</span><strong>自定义快照导入</strong></div></div>
        <p class="notice-copy">仅当七张业务表与三个受管目录全部为空时可用。上传原始 ZIP，不使用表单附件封装。</p>
        <label class="upload-drop">
          <span class="upload-icon">⇧</span><strong>{{ selectedFile?.name ?? '选择 .zip 演示快照' }}</strong><small>仅接受 ZIP 文件</small>
          <input data-test="snapshot-file" type="file" accept=".zip,application/zip" @change="selectSnapshot">
        </label>
        <button class="primary-action" data-test="import-snapshot" :disabled="store.busy || !store.isBlank || !selectedFile" @click="importCustomSnapshot">
          {{ store.busy ? '正在导入…' : '导入自定义快照' }}
        </button>
      </section>

      <section class="results-section">
        <div class="results-heading"><div><span class="step-index">05</span><strong>快照导出</strong></div></div>
        <p class="notice-copy warning-copy">导出的快照包含原始企业附件，可能涉及敏感数据。下载后请按演示资料规范保管，不要发送到无关渠道。</p>
        <button class="secondary-action" :disabled="store.busy" @click="exportSnapshot">
          {{ store.busy ? '正在导出…' : '导出演示快照（ZIP）' }}
        </button>
      </section>
    </section>
  </section>
</template>
