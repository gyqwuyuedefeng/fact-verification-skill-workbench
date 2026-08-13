<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { useReleaseStore } from '../stores/release'

const store = useReleaseStore()
const candidateVersionId = ref('')
const evaluationRunId = ref('')
const reason = ref('同条件评测通过，进入真实材料影子验证')
const keyword = ref('')
const reviewStatus = ref('')
const versionFilter = ref('')

onMounted(() => {
  store.load().catch(() => undefined)
  store.loadShadowHistory().catch(() => undefined)
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
</script>

<template>
  <section>
    <p class="eyebrow">ADMIN / SHADOW RELEASE</p>
    <div class="page-heading">
      <div>
        <h1>影子与发布</h1>
        <p class="page-lead">Candidate 不替换正式结果；正式结果始终来自 Stable，人工复核后台观察后再决定晋升或回滚。</p>
      </div>
      <span class="gate-chip" :class="store.current?.shadowEnabled ? 'pass' : 'pending'">{{ store.current?.shadowEnabled ? 'SHADOW ON' : 'SHADOW OFF' }}</span>
    </div>

    <div class="release-grid">
      <section class="panel">
        <div class="panel-heading"><span class="step-index">01</span><div><strong>发布控制</strong><small>单一 Stable · 单一 Candidate</small></div></div>
        <div v-if="store.current" class="version-card-grid">
          <div><small>REVISION</small><strong>{{ store.current.revision }}</strong></div>
          <div><small>ACTION</small><strong>{{ store.current.action }}</strong></div>
          <div class="card-wide"><small>STABLE</small><code>{{ store.current.stableVersionId }}</code></div>
          <div class="card-wide"><small>CANDIDATE</small><code>{{ store.current.candidateVersionId ?? 'NONE' }}</code></div>
        </div>
        <label class="field-label">Candidate 版本 ID</label><input v-model="candidateVersionId" class="text-input" />
        <label class="field-label">通过门禁的评测 ID</label><input v-model="evaluationRunId" class="text-input" />
        <label class="field-label">操作原因</label><input v-model="reason" class="text-input" maxlength="1000" />
        <div class="editor-actions release-actions">
          <button class="secondary-action" :disabled="!candidateVersionId || !evaluationRunId" @click="register">注册 Candidate</button>
          <button class="secondary-action" @click="change('shadow/start')">开启影子</button>
          <button class="secondary-action" @click="change('shadow/stop')">停止影子</button>
          <button class="primary-action compact" @click="change('promote')">晋升 Stable</button>
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
        <div><small>人工 PASS</small><strong>{{ store.shadowHistory?.summary.pass ?? 0 }}</strong></div>
        <div><small>人工 FAIL</small><strong>{{ store.shadowHistory?.summary.fail ?? 0 }}</strong></div>
        <div><small>差异主张</small><strong>{{ store.shadowHistory?.summary.differentClaims ?? 0 }}</strong></div>
      </div>
      <div class="shadow-filters">
        <input v-model="keyword" class="text-input" placeholder="企业或文件" />
        <select v-model="reviewStatus" class="text-input"><option value="">全部复核状态</option><option value="PENDING">PENDING</option><option value="PASS">PASS</option><option value="FAIL">FAIL</option></select>
        <input v-model="versionFilter" class="text-input" placeholder="Candidate 版本" />
      </div>
      <article v-for="item in filteredItems" :key="item.shadowRunId" class="shadow-run-row">
        <div><small>{{ formatTime(item.createdAt) }}</small><strong>{{ item.companyNames || '主体待确认' }}</strong><span>{{ item.fileName }}</span></div>
        <div><small>Stable / Candidate</small><code>{{ item.stableVersionId }}</code><code>{{ item.candidateVersionId }}</code></div>
        <div><small>观察结果</small><strong>{{ item.reviewStatus }}</strong><span>一致 {{ item.agreementCount }} · 主张差异 {{ item.differenceCount }}</span></div>
        <div v-if="item.reviewStatus === 'PENDING'" class="shadow-review-actions"><button @click="store.reviewShadow(item.shadowRunId, 'PASS', '人工对照完成')">PASS</button><button @click="store.reviewShadow(item.shadowRunId, 'FAIL', '发现需要修正的差异')">FAIL</button></div>
      </article>
      <div v-if="!filteredItems.length" class="empty-results">开启影子后，Stable 普通任务会自动产生后台 Candidate 观察记录。</div>
    </section>
  </section>
</template>
