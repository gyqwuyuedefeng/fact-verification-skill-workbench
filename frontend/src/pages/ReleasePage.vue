<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { skillVersionLabel, statusLabel } from '../presentation/labels'
import { useEvaluationStore } from '../stores/evaluation'
import { useReleaseStore } from '../stores/release'
import { useSkillStore } from '../stores/skill'

const store = useReleaseStore()
const skillStore = useSkillStore()
const evaluationStore = useEvaluationStore()
const candidateVersionId = ref('')
const evaluationRunId = ref('')
const reason = ref('同条件评测通过，进入真实材料影子验证')
const keyword = ref('')
const reviewStatus = ref('')
const versionFilter = ref('')

onMounted(() => {
  store.load().catch(() => undefined)
  store.loadShadowHistory().catch(() => undefined)
  skillStore.load().catch(() => undefined)
  evaluationStore.loadHistory().catch(() => undefined)
})

const candidateVersions = computed(() => skillStore.versions.filter((version) => version.status === 'CANDIDATE'))
const eligibleEvaluations = computed(() => evaluationStore.history.filter((run) =>
  run.status === 'COMPLETED' &&
  run.gateStatus === 'PASS' &&
  (run.variants ?? []).some((variant) => variant.identifier === candidateVersionId.value),
))

/** 切换候选版后，旧评测不再属于当前关联范围，必须重新选择。 */
watch(candidateVersionId, () => {
  evaluationRunId.value = ''
})

const filteredItems = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return (store.shadowHistory?.items ?? []).filter((item) => {
    const matchesKeyword = !key || `${item.fileName} ${item.companyNames}`.toLowerCase().includes(key)
    const matchesStatus = !reviewStatus.value || item.reviewStatus === reviewStatus.value
    const matchesVersion = !versionFilter.value || item.candidateVersionId.includes(versionFilter.value.trim())
    return matchesKeyword && matchesStatus && matchesVersion
  })
})

async function register() {
  if (!candidateVersionId.value || !evaluationRunId.value) return
  await store.register(candidateVersionId.value, evaluationRunId.value, reason.value).catch(() => undefined)
}

async function change(action: 'shadow/start' | 'shadow/stop' | 'promote' | 'rollback') {
  await store.change(action, reason.value).catch(() => undefined)
}

function formatTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function releaseActionLabel(action: string): string {
  const labels: Record<string, string> = {
    INITIALIZE: '初始化',
    REGISTER: '注册候选版',
    SHADOW_START: '开启影子',
    SHADOW_STOP: '停止影子',
    PROMOTE: '晋升稳定版',
    ROLLBACK: '回滚上一版',
  }
  return `${labels[action] ?? '未知操作'}（${action}）`
}
</script>

<template>
  <section>
    <p class="eyebrow">ADMIN / SHADOW RELEASE</p>
    <div class="page-heading">
      <div>
        <h1>影子与发布</h1>
        <p class="page-lead">候选版（CANDIDATE）不替换正式结果；正式结果始终来自稳定版（STABLE），人工复核后台观察后再决定晋升或回滚。</p>
      </div>
      <span class="gate-chip" :class="store.current?.shadowEnabled ? 'pass' : 'pending'">{{ store.current?.shadowEnabled ? '影子已开启（SHADOW ON）' : '影子已关闭（SHADOW OFF）' }}</span>
    </div>

    <div class="release-grid">
      <section class="panel">
        <div class="panel-heading"><span class="step-index">01</span><div><strong>发布控制</strong><small>单一稳定版（STABLE）· 单一候选版（CANDIDATE）</small></div></div>
        <div v-if="store.current" class="version-card-grid">
          <div><small>修订号（REVISION）</small><strong>{{ store.current.revision }}</strong></div>
          <div><small>操作（ACTION）</small><strong>{{ releaseActionLabel(store.current.action) }}</strong></div>
          <div class="card-wide"><small>正式版（STABLE）</small><code>{{ store.current.stableVersionId }}</code></div>
          <div class="card-wide"><small>候选版（CANDIDATE）</small><code>{{ store.current.candidateVersionId ?? '无' }}</code></div>
        </div>
        <label class="field-label" for="release-candidate-version">候选版（CANDIDATE）</label>
        <select id="release-candidate-version" v-model="candidateVersionId" class="text-input" data-test="release-candidate-version">
          <option value="">请选择候选版</option>
          <option v-for="version in candidateVersions" :key="version.id" :value="version.id">{{ skillVersionLabel(version) }}</option>
        </select>
        <label class="field-label" for="release-evaluation-run">已通过门禁的评测</label>
        <select id="release-evaluation-run" v-model="evaluationRunId" class="text-input" data-test="release-evaluation-run" :disabled="!candidateVersionId">
          <option value="">请选择评测</option>
          <option v-for="run in eligibleEvaluations" :key="run.id" :value="run.id">{{ run.id }} · {{ statusLabel(run.status) }} · 人工{{ statusLabel(run.gateStatus) }}</option>
        </select>
        <label class="field-label">操作原因</label><input v-model="reason" class="text-input" maxlength="1000" />
        <div class="editor-actions release-actions">
          <button class="secondary-action" :disabled="!candidateVersionId || !evaluationRunId" @click="register">注册候选版（CANDIDATE）</button>
          <button class="secondary-action" @click="change('shadow/start')">开启影子</button>
          <button class="secondary-action" @click="change('shadow/stop')">停止影子</button>
          <button class="primary-action compact" @click="change('promote')">晋升稳定版（STABLE）</button>
          <button class="secondary-action" @click="change('rollback')">回滚上一版</button>
        </div>
        <p v-if="store.error" class="error-message">{{ store.error }}</p>
      </section>

      <section class="panel release-history-panel">
        <div class="panel-heading"><span class="step-index">02</span><div><strong>发布历史</strong><small>追加记录，不覆盖</small></div></div>
        <div v-for="item in store.history" :key="item.revision" class="version-row"><span class="version-status">R{{ item.revision }}</span><div><strong>{{ item.action }}</strong><small>{{ item.reason }}</small></div><code>{{ formatTime(item.createdAt) }}</code></div>
        <div v-if="!store.history.length" class="compact-empty">暂无发布记录。</div>
      </section>
    </div>

    <section class="results-section shadow-history-section">
      <div class="results-heading"><div><span class="step-index">03</span><strong>真实材料影子观察</strong></div><small>影子真实材料没有金标，不计算准确率</small></div>
      <div class="shadow-summary">
        <div><small>总任务</small><strong>{{ store.shadowHistory?.summary.total ?? 0 }}</strong></div>
        <div><small>运行完成</small><strong>{{ store.shadowHistory?.summary.completed ?? 0 }}</strong></div>
        <div><small>人工通过（PASS）</small><strong>{{ store.shadowHistory?.summary.pass ?? 0 }}</strong></div>
        <div><small>人工未通过（FAIL）</small><strong>{{ store.shadowHistory?.summary.fail ?? 0 }}</strong></div>
        <div><small>差异主张</small><strong>{{ store.shadowHistory?.summary.differentClaims ?? 0 }}</strong></div>
      </div>
      <div class="shadow-filters">
        <input v-model="keyword" class="text-input" placeholder="企业或文件" />
        <select v-model="reviewStatus" class="text-input"><option value="">全部复核状态</option><option value="PENDING">{{ statusLabel('PENDING') }}</option><option value="PASS">{{ statusLabel('PASS') }}</option><option value="FAIL">{{ statusLabel('FAIL') }}</option></select>
        <input v-model="versionFilter" class="text-input" placeholder="候选版（CANDIDATE）版本" />
      </div>
      <article v-for="item in filteredItems" :key="item.shadowRunId" class="shadow-run-row">
        <div><small>{{ formatTime(item.createdAt) }}</small><strong>{{ item.companyNames || '主体待确认' }}</strong><span>{{ item.fileName }}</span></div>
        <div><small>稳定版（STABLE）/ 候选版（CANDIDATE）</small><code>{{ item.stableVersionId }}</code><code>{{ item.candidateVersionId }}</code></div>
        <div><small>观察结果</small><strong>{{ statusLabel(item.reviewStatus) }}</strong><span>一致 {{ item.agreementCount }} · 主张差异 {{ item.differenceCount }}</span></div>
        <div v-if="item.reviewStatus === 'PENDING'" class="shadow-review-actions"><button @click="store.reviewShadow(item.shadowRunId, 'PASS', '人工对照完成')">人工通过（PASS）</button><button @click="store.reviewShadow(item.shadowRunId, 'FAIL', '发现需要修正的差异')">人工未通过（FAIL）</button></div>
      </article>
      <div v-if="!filteredItems.length" class="empty-results">开启影子后，稳定版（STABLE）普通任务会自动产生后台候选版（CANDIDATE）观察记录。</div>
    </section>
  </section>
</template>
