<script setup lang="ts">
import { computed, inject, onMounted, onUnmounted, ref, watch } from 'vue'
import { routeLocationKey, routerKey } from 'vue-router'

import { reportUrl } from '../api/evaluation'
import { metricLabel, shortId, skillVersionLabel, statusLabel, variantLabel } from '../presentation/labels'
import { useEvaluationStore } from '../stores/evaluation'
import { useSkillStore } from '../stores/skill'
import type { CoreMetrics, EvaluationSample, MetricValue } from '../types/evaluation'

const store = useEvaluationStore()
const skillStore = useSkillStore()
const route = inject(routeLocationKey, null)
const router = inject(routerKey, null)
const activeTab = ref<'runs' | 'version' | 'compare'>('runs')
const datasetVersion = ref('public-tech-2024-v3')
const stableVersionId = ref('')
const candidateVersionId = ref('')
const summaryVersionId = ref('')
const leftVersionId = ref('')
const rightVersionId = ref('')
const lastRefreshedAt = ref<string | null>(null)
let pollTimer: ReturnType<typeof globalThis.setTimeout> | undefined
let pollGeneration = 0

const stableVersions = computed(() => skillStore.versions.filter((item) => item.status === 'STABLE'))
const candidateVersions = computed(() => skillStore.versions.filter((item) => item.status === 'CANDIDATE'))
const frozenVersions = computed(() => skillStore.versions.filter((item) => item.status !== 'DRAFT'))
const activeRun = computed(() => ['PENDING', 'RUNNING'].includes(store.evaluation?.status ?? ''))
const stableContractValid = computed(() => stableVersions.value.length <= 1)
const canStart = computed(() => Boolean(candidateVersionId.value)
  && stableContractValid.value
  && (stableVersions.value.length === 0 || stableVersionId.value === stableVersions.value[0]?.id))

/** 当前生命周期只有唯一 Stable 时立即固定选择；零个 Stable 保留首次建版，两条以上则失败关闭创建按钮。 */
watch(stableVersions, (versions) => {
  stableVersionId.value = versions.length === 1 ? versions[0]!.id : ''
}, { immediate: true })

const metricRows = computed(() => Object.entries(store.evaluation?.metrics ?? {}))
const modelParameters = computed(() => {
  const value = store.evaluation?.runManifest?.modelParameters
  if (!value || typeof value !== 'object' || Array.isArray(value)) return '待选择'
  const parameters = value as Record<string, unknown>
  return `temperature=${parameters.temperature}, topP=${parameters.topP}, seed=${parameters.seed}, parallelToolCalls=${parameters.parallelToolCalls}, maxTokens=${parameters.maxTokens}, enableThinking=${parameters.enableThinking}`
})

async function start() {
  if (!canStart.value || store.creating) return
  stopPolling()
  const created = await store.start(datasetVersion.value, variantsForRun()).catch(() => null)
  if (!created) return
  await router?.replace({ query: { evaluationId: created.id } })
  await refreshAndTrack(created.id)
  if (activeRun.value) startPolling(created.id)
}

function variantsForRun(): string[] {
  return ['BASELINE', ...(stableVersionId.value ? [stableVersionId.value] : []), candidateVersionId.value]
}

function stopPolling() {
  pollGeneration += 1
  if (pollTimer !== undefined) {
    globalThis.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

async function refreshAndTrack(id: string) {
  await store.refreshEvaluation(id)
  if (store.evaluation?.id === id) lastRefreshedAt.value = new Date().toISOString()
}

function startPolling(id: string) {
  stopPolling()
  schedulePolling(id, pollGeneration)
}

function schedulePolling(id: string, generation: number) {
  pollTimer = globalThis.setTimeout(async () => {
    pollTimer = undefined
    await refreshAndTrack(id).catch(() => undefined)
    if (generation !== pollGeneration || !activeRun.value || store.evaluation?.id !== id) {
      if (generation === pollGeneration) stopPolling()
      return
    }
    schedulePolling(id, generation)
  }, 5_000)
}

async function selectEvaluation(id: string) {
  stopPolling()
  await router?.replace({ query: { evaluationId: id } })
  await refreshAndTrack(id).catch(() => undefined)
  if (activeRun.value) startPolling(id)
}

async function refreshCurrentEvaluation() {
  if (!store.evaluation) return
  const evaluationId = store.evaluation.id
  stopPolling()
  await refreshAndTrack(evaluationId).catch(() => undefined)
  if (activeRun.value && store.evaluation?.id === evaluationId) startPolling(evaluationId)
}

onMounted(async () => {
  void skillStore.load().catch(() => undefined)
  void store.loadHistory().catch(() => undefined)
  const evaluationId = typeof route?.query.evaluationId === 'string' ? route.query.evaluationId : null
  if (!evaluationId) return
  const generation = pollGeneration
  await refreshAndTrack(evaluationId).catch(() => undefined)
  if (generation === pollGeneration && activeRun.value && store.evaluation?.id === evaluationId) {
    startPolling(evaluationId)
  }
})

onUnmounted(stopPolling)

function runningDuration(value?: string | null) {
  if (!value) return '刚刚开始'
  const elapsedSeconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1_000))
  const minutes = Math.floor(elapsedSeconds / 60)
  const seconds = elapsedSeconds % 60
  return minutes ? `${minutes} 分 ${seconds} 秒` : `${seconds} 秒`
}

function display(metric: MetricValue): string {
  return `${metric.numerator}/${metric.denominator} · ${(metric.value * 100).toFixed(1)}%`
}

function formatTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '运行中'
}

function delta(value: number) {
  const sign = value > 0 ? '+' : ''
  return `${sign}${(value * 100).toFixed(1)}pp`
}

function attemptStatus(result: EvaluationSample['variantResults'][string]) {
  const attempt = result.attempts?.[0]
  const value = attempt?.errorCode ?? attempt?.output?.claims?.[0]?.status
  return value ? statusLabel(value) : '无合法主张'
}

function rawOutput(value: unknown) {
  return JSON.stringify(value ?? null, null, 2)
}

const metricColumns: Array<{ key: keyof CoreMetrics }> = [
  { key: 'accuracy' },
  { key: 'completionRate' },
  { key: 'stability' },
  { key: 'humanInterventionRate' },
]
</script>

<template>
  <section>
    <p class="eyebrow">ADMIN / EVALUATION</p>
    <div class="page-heading">
      <div>
        <h1>管理评测</h1>
        <p class="page-lead">用评测批次保留每次同条件事实，用版本汇总串联历史，用直接对比回答这一版到底改好了什么。</p>
      </div>
      <span class="gate-chip" :class="store.evaluation?.gateStatus.toLowerCase()">GATE {{ statusLabel(store.evaluation?.gateStatus) }}</span>
    </div>

    <nav class="admin-tabs" aria-label="评测视图">
      <button data-test="evaluation-tab-runs" :class="{ active: activeTab === 'runs' }" @click="activeTab = 'runs'">评测批次</button>
      <button data-test="evaluation-tab-version" :class="{ active: activeTab === 'version' }" @click="activeTab = 'version'">按版本汇总</button>
      <button data-test="evaluation-tab-compare" :class="{ active: activeTab === 'compare' }" @click="activeTab = 'compare'">版本对比</button>
    </nav>

    <template v-if="activeTab === 'runs'">
      <div class="admin-evaluation-grid">
        <aside class="panel history-panel">
          <div class="panel-heading"><span class="step-index">H</span><div><strong>历史评测</strong><small>不可覆盖 · 新到旧</small></div></div>
          <button v-for="item in store.history" :key="item.id" class="history-row" :class="{ active: item.id === store.evaluation?.id }" @click="selectEvaluation(item.id)">
            <span :class="['gate-dot', item.gateStatus.toLowerCase()]"></span>
            <div><strong>{{ formatTime(item.createdAt) }}</strong><small>{{ item.datasetVersion }} · {{ item.sampleCount }} 条 · {{ item.variants?.length ?? 0 }} 变体</small></div>
            <code>{{ shortId(item.id) }}</code>
          </button>
          <div v-if="!store.history.length" class="compact-empty">暂无历史；可先在右侧创建评测。</div>
        </aside>

        <div class="evaluation-main">
          <div class="evaluation-layout">
            <section class="panel evaluation-launcher">
              <div class="panel-heading"><span class="step-index">01</span><div><strong>新建同条件评测</strong><small>唯一变化：基线指令或冻结 Skill</small></div></div>
              <label class="field-label" for="dataset-version">金标数据集</label>
              <input id="dataset-version" v-model="datasetVersion" class="text-input" readonly />
              <label class="field-label" for="stable-version">稳定版（STABLE）版本</label>
              <select id="stable-version" v-model="stableVersionId" data-test="stable-version" class="text-input" :disabled="stableVersions.length <= 1">
                <option v-if="stableVersions.length === 0" value="">尚无正式版（首次建版）</option>
                <option v-else-if="stableVersions.length > 1" value="">正式版状态异常，请刷新</option>
                <option v-for="item in stableVersions" :key="item.id" :value="item.id">{{ skillVersionLabel(item) }}</option>
              </select>
              <label class="field-label" for="candidate-version">候选版（CANDIDATE）版本</label>
              <select id="candidate-version" v-model="candidateVersionId" data-test="candidate-version" class="text-input">
                <option value="" disabled>请选择候选版</option>
                <option v-for="item in candidateVersions" :key="item.id" :value="item.id">{{ skillVersionLabel(item) }}</option>
              </select>
              <button class="primary-action" data-test="start-evaluation" :disabled="!canStart || store.creating" @click="start">
                {{ store.creating ? '正在创建评测…' : stableVersionId ? `运行${statusLabel('BASELINE')} + ${statusLabel('STABLE')} + ${statusLabel('CANDIDATE')}` : `运行${statusLabel('BASELINE')} + 首个${statusLabel('CANDIDATE')}` }}
              </button>
              <p v-if="store.error" class="error-message">{{ store.error }}</p>
            </section>

            <section class="panel locked-conditions">
              <div class="panel-heading"><span class="step-index">02</span><div><strong>同条件锁定</strong><small>Run Manifest 可复现识别值</small></div></div>
              <dl>
                <div><dt>DATASET</dt><dd>{{ store.evaluation?.datasetHash ?? '待选择' }}</dd></div>
                <div><dt>MODEL</dt><dd>{{ store.evaluation?.runManifest?.modelConfigHash ?? '待选择' }}</dd></div>
                <div><dt>PARAMS</dt><dd>{{ modelParameters }}</dd></div>
                <div><dt>TOOLS</dt><dd>{{ store.evaluation?.runManifest?.toolContractHash ?? '待选择' }}</dd></div>
                <div><dt>EVIDENCE</dt><dd>{{ store.evaluation?.runManifest?.evidenceSnapshotHash ?? '待选择' }}</dd></div>
                <div><dt>OUTPUT</dt><dd>{{ store.evaluation?.runManifest?.outputSchemaHash ?? '待选择' }}</dd></div>
              </dl>
              <button v-if="store.evaluation" class="secondary-action" @click="refreshCurrentEvaluation">刷新评测状态</button>
            </section>
          </div>

          <section v-if="activeRun" class="panel evaluation-running-card" data-test="evaluation-running-card">
            <span class="status-spinner" aria-hidden="true">◌</span>
            <div><strong>{{ statusLabel(store.evaluation?.status) }}</strong><small>已运行 {{ runningDuration(store.evaluation?.createdAt) }} · 最近刷新 {{ lastRefreshedAt ? formatTime(lastRefreshedAt) : '待刷新' }}</small></div>
          </section>

          <section class="results-section evaluation-results">
            <div class="results-heading">
              <div><span class="step-index">03</span><strong>四项核心指标</strong></div>
              <div v-if="store.evaluation?.status === 'COMPLETED'" class="report-links">
                <a :href="reportUrl(store.evaluation.id, 'markdown')">导出报告 Markdown</a><a :href="reportUrl(store.evaluation.id, 'json')">导出报告 JSON</a>
              </div>
              <small v-else-if="store.evaluation">{{ statusLabel(store.evaluation.status) }}</small>
              <small v-else>选择一个历史批次</small>
            </div>
            <div v-if="!metricRows.length" class="empty-results">这里展示每项指标的定义、分子、分母和百分比。</div>
            <div v-else class="metrics-table">
              <div class="metric-row metric-head"><span>变体</span><span v-for="column in metricColumns" :key="column.key">{{ metricLabel(column.key) }}</span></div>
              <div v-for="[variant, metrics] in metricRows" :key="variant" class="metric-row">
                <strong>{{ variantLabel(variant) }}</strong><span v-for="column in metricColumns" :key="column.key" :title="metrics[column.key].definition">{{ display(metrics[column.key]) }}</span>
              </div>
            </div>
          </section>

          <div class="evaluation-bottom-grid">
            <section class="panel"><div class="panel-heading"><span class="step-index">04</span><div><strong>单样本下钻</strong><small>金标、各变体评分与原始输出</small></div></div><div v-if="!store.samples.length" class="compact-empty">选择完成批次后加载样本。</div><details v-for="sample in store.samples" :key="sample.sampleId" class="sample-drilldown"><summary><code>{{ sample.sampleId }}</code><span>金标 {{ statusLabel(sample.gold?.expectedStatus) }}</span></summary><p>{{ sample.gold?.material?.text ?? '材料文本未写入报告' }}</p><article v-for="[variant, result] in Object.entries(sample.variantResults)" :key="variant" class="sample-variant-result"><code>{{ variantLabel(variant) }}</code><strong :class="{ failed: !result.score?.accurate }">{{ result.score?.accurate ? '评分通过' : '评分失败' }}</strong><span>{{ attemptStatus(result) }}</span><small>{{ result.attempts?.[0]?.durationMs ?? 0 }} ms · {{ result.attempts?.length ?? 0 }} 次</small><details class="raw-attempt"><summary>查看原始输出</summary><pre>{{ rawOutput(result.attempts?.[0]?.output) }}</pre></details></article></details></section>
            <section class="panel"><div class="panel-heading"><span class="step-index">05</span><div><strong>候选版（CANDIDATE）门禁</strong><small>硬检查原始原因</small></div></div><div v-if="!store.evaluation?.gateReasons?.length" class="compact-empty">等待门禁。</div><div v-for="check in store.evaluation?.gateReasons ?? []" :key="check.name" class="gate-row"><i :class="{ passed: check.passed }"></i><div><strong>{{ check.name }}</strong><small>{{ check.reason }}</small></div></div></section>
          </div>
        </div>
      </div>
    </template>

    <section v-else-if="activeTab === 'version'" class="panel admin-workspace">
      <div class="panel-heading"><span class="step-index">V</span><div><strong>一个版本的全部评测</strong><small>每个数字都回到原始批次，不跨条件平均</small></div></div>
      <div class="inline-query"><select v-model="summaryVersionId" data-test="summary-version" class="text-input"><option value="" disabled>请选择冻结版本</option><option v-for="item in frozenVersions" :key="item.id" :value="item.id">{{ skillVersionLabel(item) }}</option></select><button class="primary-action compact" :disabled="!summaryVersionId" @click="store.loadVersionSummary(summaryVersionId)">汇总</button></div>
      <div v-if="store.versionSummary" class="summary-strip">
        <div><small>参评次数</small><strong>{{ store.versionSummary.evaluationCount }}</strong></div>
        <div><small>最新评测</small><code>{{ store.versionSummary.latestEvaluationId }}</code></div>
        <div><small>正式门禁评测</small><code>{{ store.versionSummary.registeredEvaluationId ?? '尚未注册' }}</code></div>
      </div>
      <button v-for="item in store.versionSummary?.evaluations ?? []" :key="item.id" class="version-evaluation-row" @click="selectEvaluation(item.id); activeTab = 'runs'">
        <div><strong>{{ formatTime(item.createdAt) }}</strong><small>{{ item.datasetVersion }} · GATE {{ statusLabel(item.gateStatus) }}</small></div><span v-if="item.id === store.versionSummary?.registeredEvaluationId" class="stable-chip">正式门禁</span><code>{{ shortId(item.id) }}</code>
      </button>
    </section>

    <section v-else class="panel admin-workspace">
      <div class="panel-heading"><span class="step-index">Δ</span><div><strong>当前版本 vs 上一版 / 稳定版（STABLE）</strong><small>只在共同评测批次中给出直接优劣</small></div></div>
      <div class="compare-query"><select v-model="leftVersionId" data-test="compare-left-version" class="text-input"><option value="" disabled>请选择基准版本</option><option v-for="item in frozenVersions" :key="item.id" :value="item.id">{{ skillVersionLabel(item) }}</option></select><span>→</span><select v-model="rightVersionId" data-test="compare-right-version" class="text-input"><option value="" disabled>请选择目标版本</option><option v-for="item in frozenVersions" :key="item.id" :value="item.id">{{ skillVersionLabel(item) }}</option></select><button class="primary-action compact" data-test="compare-versions" :disabled="!leftVersionId || !rightVersionId" @click="store.compareVersions(leftVersionId, rightVersionId)">对比</button></div>
      <template v-if="store.comparison">
        <div v-if="!store.comparison.comparable" class="comparison-warning"><strong>暂无共同评测</strong><span v-for="reason in store.comparison.reasons" :key="reason">{{ reason }}</span></div>
        <template v-else>
          <p class="page-lead">共同评测批次 <code>{{ store.comparison.evaluationRunId }}</code></p>
          <div class="delta-grid"><div v-for="(value, key) in store.comparison.metricDeltas" :key="key"><small>{{ metricLabel(key) }}</small><strong :class="{ positive: value > 0, negative: value < 0 }">{{ delta(value) }}</strong></div></div>
          <div class="summary-strip"><div><small>目标版胜</small><strong>{{ store.comparison.sampleOutcomes.rightWins ?? 0 }}</strong></div><div><small>基准版胜</small><strong>{{ store.comparison.sampleOutcomes.leftWins ?? 0 }}</strong></div><div><small>持平</small><strong>{{ store.comparison.sampleOutcomes.ties ?? 0 }}</strong></div></div>
        </template>
      </template>
    </section>
  </section>
</template>

<style scoped>
.evaluation-running-card {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
}

.evaluation-running-card div {
  display: grid;
  gap: 5px;
}

.evaluation-running-card small {
  color: #6e8399;
}

.status-spinner {
  display: inline-grid;
  width: 24px;
  height: 24px;
  place-items: center;
  color: #48d6c7;
  font-size: 24px;
  line-height: 1;
  animation: evaluation-status-spin 900ms linear infinite;
}

@keyframes evaluation-status-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
