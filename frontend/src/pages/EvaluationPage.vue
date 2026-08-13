<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { reportUrl } from '../api/evaluation'
import { useEvaluationStore } from '../stores/evaluation'
import type { CoreMetrics, EvaluationSample, MetricValue } from '../types/evaluation'

const store = useEvaluationStore()
const activeTab = ref<'runs' | 'version' | 'compare'>('runs')
const datasetVersion = ref('public-tech-2024-v3')
const stableId = ref('')
const candidateId = ref('')
const initialStable = ref(false)
const summaryVersionId = ref('')
const leftVersionId = ref('')
const rightVersionId = ref('')

onMounted(() => store.loadHistory().catch(() => undefined))

const metricRows = computed(() => Object.entries(store.evaluation?.metrics ?? {}))
const modelParameters = computed(() => {
  const value = store.evaluation?.runManifest?.modelParameters
  if (!value || typeof value !== 'object' || Array.isArray(value)) return '待选择'
  const parameters = value as Record<string, unknown>
  return `temperature=${parameters.temperature}, topP=${parameters.topP}, seed=${parameters.seed}, parallelToolCalls=${parameters.parallelToolCalls}, maxTokens=${parameters.maxTokens}, enableThinking=${parameters.enableThinking}`
})

async function start() {
  if ((!initialStable.value && !stableId.value) || !candidateId.value || store.busy) return
  const variants = initialStable.value
    ? ['BASELINE', candidateId.value]
    : ['BASELINE', stableId.value, candidateId.value]
  await store.start(datasetVersion.value, variants).catch(() => undefined)
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
  return attempt?.errorCode ?? attempt?.output?.claims?.[0]?.status ?? '无合法主张'
}

function rawOutput(value: unknown) {
  return JSON.stringify(value ?? null, null, 2)
}

const metricColumns: Array<{ key: keyof CoreMetrics; label: string }> = [
  { key: 'accuracy', label: '准确率' },
  { key: 'completionRate', label: '任务完成率' },
  { key: 'stability', label: '稳定性' },
  { key: 'humanInterventionRate', label: '人工介入率' },
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
      <span class="gate-chip" :class="store.evaluation?.gateStatus.toLowerCase()">GATE {{ store.evaluation?.gateStatus ?? 'PENDING' }}</span>
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
          <button v-for="item in store.history" :key="item.id" class="history-row" :class="{ active: item.id === store.evaluation?.id }" @click="store.selectEvaluation(item.id)">
            <span :class="['gate-dot', item.gateStatus.toLowerCase()]"></span>
            <div><strong>{{ formatTime(item.createdAt) }}</strong><small>{{ item.datasetVersion }} · {{ item.sampleCount }} 条 · {{ item.variants?.length ?? 0 }} 变体</small></div>
            <code>{{ item.id.slice(0, 8) }}</code>
          </button>
          <div v-if="!store.history.length" class="compact-empty">暂无历史；可先在右侧创建评测。</div>
        </aside>

        <div class="evaluation-main">
          <div class="evaluation-layout">
            <section class="panel evaluation-launcher">
              <div class="panel-heading"><span class="step-index">01</span><div><strong>新建同条件评测</strong><small>唯一变化：基线指令或冻结 Skill</small></div></div>
              <label class="field-label" for="dataset-version">金标数据集</label>
              <input id="dataset-version" v-model="datasetVersion" class="text-input" readonly />
              <label class="shadow-option"><input v-model="initialStable" data-test="initial-stable" type="checkbox" /><span>首次建立 Stable（BASELINE + 首个 Candidate）</span></label>
              <label class="field-label" for="stable-id">Stable 版本 ID</label>
              <input id="stable-id" v-model="stableId" data-test="stable-id" class="text-input" :disabled="initialStable" placeholder="冻结 Stable ID" />
              <label class="field-label" for="candidate-id">Candidate 版本 ID</label>
              <input id="candidate-id" v-model="candidateId" data-test="candidate-id" class="text-input" placeholder="冻结 Candidate ID" />
              <button class="primary-action" data-test="start-evaluation" :disabled="(!initialStable && !stableId) || !candidateId || store.busy" @click="start">
                {{ store.busy ? '评测运行中…' : initialStable ? '运行 BASELINE + 首个 Candidate' : '运行 BASELINE + Stable + Candidate' }}
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
              <button v-if="store.evaluation" class="secondary-action" @click="store.refresh">刷新评测状态</button>
            </section>
          </div>

          <section class="results-section evaluation-results">
            <div class="results-heading">
              <div><span class="step-index">03</span><strong>四项核心指标</strong></div>
              <div v-if="store.evaluation?.status === 'COMPLETED'" class="report-links">
                <a :href="reportUrl(store.evaluation.id, 'markdown')">导出报告 Markdown</a><a :href="reportUrl(store.evaluation.id, 'json')">导出报告 JSON</a>
              </div>
              <small v-else>{{ store.evaluation?.status ?? '选择一个历史批次' }}</small>
            </div>
            <div v-if="!metricRows.length" class="empty-results">这里展示每项指标的定义、分子、分母和百分比。</div>
            <div v-else class="metrics-table">
              <div class="metric-row metric-head"><span>变体</span><span v-for="column in metricColumns" :key="column.key">{{ column.label }}</span></div>
              <div v-for="[variant, metrics] in metricRows" :key="variant" class="metric-row">
                <strong>{{ variant }}</strong><span v-for="column in metricColumns" :key="column.key" :title="metrics[column.key].definition">{{ display(metrics[column.key]) }}</span>
              </div>
            </div>
          </section>

          <div class="evaluation-bottom-grid">
            <section class="panel"><div class="panel-heading"><span class="step-index">04</span><div><strong>单样本下钻</strong><small>金标、各变体评分与原始输出</small></div></div><div v-if="!store.samples.length" class="compact-empty">选择完成批次后加载样本。</div><details v-for="sample in store.samples" :key="sample.sampleId" class="sample-drilldown"><summary><code>{{ sample.sampleId }}</code><span>金标 {{ sample.gold?.expectedStatus ?? 'UNKNOWN' }}</span></summary><p>{{ sample.gold?.material?.text ?? '材料文本未写入报告' }}</p><article v-for="[variant, result] in Object.entries(sample.variantResults)" :key="variant" class="sample-variant-result"><code>{{ variant }}</code><strong :class="{ failed: !result.score?.accurate }">{{ result.score?.accurate ? '评分通过' : '评分失败' }}</strong><span>{{ attemptStatus(result) }}</span><small>{{ result.attempts?.[0]?.durationMs ?? 0 }} ms · {{ result.attempts?.length ?? 0 }} 次</small><details class="raw-attempt"><summary>查看原始输出</summary><pre>{{ rawOutput(result.attempts?.[0]?.output) }}</pre></details></article></details></section>
            <section class="panel"><div class="panel-heading"><span class="step-index">05</span><div><strong>Candidate 门禁</strong><small>硬检查原始原因</small></div></div><div v-if="!store.evaluation?.gateReasons?.length" class="compact-empty">等待门禁。</div><div v-for="check in store.evaluation?.gateReasons ?? []" :key="check.name" class="gate-row"><i :class="{ passed: check.passed }"></i><div><strong>{{ check.name }}</strong><small>{{ check.reason }}</small></div></div></section>
          </div>
        </div>
      </div>
    </template>

    <section v-else-if="activeTab === 'version'" class="panel admin-workspace">
      <div class="panel-heading"><span class="step-index">V</span><div><strong>一个版本的全部评测</strong><small>每个数字都回到原始批次，不跨条件平均</small></div></div>
      <div class="inline-query"><input v-model="summaryVersionId" class="text-input" placeholder="输入 Skill 版本 ID" /><button class="primary-action compact" :disabled="!summaryVersionId" @click="store.loadVersionSummary(summaryVersionId)">汇总</button></div>
      <div v-if="store.versionSummary" class="summary-strip">
        <div><small>参评次数</small><strong>{{ store.versionSummary.evaluationCount }}</strong></div>
        <div><small>最新评测</small><code>{{ store.versionSummary.latestEvaluationId }}</code></div>
        <div><small>正式门禁评测</small><code>{{ store.versionSummary.registeredEvaluationId ?? '尚未注册' }}</code></div>
      </div>
      <button v-for="item in store.versionSummary?.evaluations ?? []" :key="item.id" class="version-evaluation-row" @click="store.selectEvaluation(item.id); activeTab = 'runs'">
        <div><strong>{{ formatTime(item.createdAt) }}</strong><small>{{ item.datasetVersion }} · GATE {{ item.gateStatus }}</small></div><span v-if="item.id === store.versionSummary?.registeredEvaluationId" class="stable-chip">正式门禁</span><code>{{ item.id }}</code>
      </button>
    </section>

    <section v-else class="panel admin-workspace">
      <div class="panel-heading"><span class="step-index">Δ</span><div><strong>当前版本 vs 上一版 / Stable</strong><small>只在共同评测批次中给出直接优劣</small></div></div>
      <div class="compare-query"><input v-model="leftVersionId" class="text-input" placeholder="基准版本 ID" /><span>→</span><input v-model="rightVersionId" class="text-input" placeholder="目标版本 ID" /><button class="primary-action compact" :disabled="!leftVersionId || !rightVersionId" @click="store.compareVersions(leftVersionId, rightVersionId)">对比</button></div>
      <template v-if="store.comparison">
        <div v-if="!store.comparison.comparable" class="comparison-warning"><strong>不可直接比较</strong><span v-for="reason in store.comparison.reasons" :key="reason">{{ reason }}</span></div>
        <template v-else>
          <p class="page-lead">共同评测批次 <code>{{ store.comparison.evaluationRunId }}</code></p>
          <div class="delta-grid"><div v-for="(value, key) in store.comparison.metricDeltas" :key="key"><small>{{ key }}</small><strong :class="{ positive: value > 0, negative: value < 0 }">{{ delta(value) }}</strong></div></div>
          <div class="summary-strip"><div><small>目标版胜</small><strong>{{ store.comparison.sampleOutcomes.rightWins ?? 0 }}</strong></div><div><small>基准版胜</small><strong>{{ store.comparison.sampleOutcomes.leftWins ?? 0 }}</strong></div><div><small>持平</small><strong>{{ store.comparison.sampleOutcomes.ties ?? 0 }}</strong></div></div>
        </template>
      </template>
    </section>
  </section>
</template>
