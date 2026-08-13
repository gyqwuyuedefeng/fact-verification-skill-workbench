<script setup lang="ts">
import { computed, ref } from 'vue'

import { useVerificationStore } from '../stores/verification'
import type {
  VerificationClaim,
  VerificationConversation,
} from '../types/verification'

const store = useVerificationStore()
const message = ref('')
const selectedFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const executionMode = ref<'BASELINE' | 'STABLE'>('BASELINE')

/** 兼容已有结果状态的预览；真实发送后每次任务都保留为独立会话卡。 */
const conversations = computed<VerificationConversation[]>(() => {
  if (store.conversations.length) return store.conversations
  if (!store.claims.length) return []
  return [{
    id: 'preview',
    message: '',
    fileName: store.task?.fileName ?? null,
    executionMode: store.task?.executionMode ?? 'STABLE',
    task: store.task,
    claims: store.claims,
    events: store.events,
    error: store.error,
  }]
})

const canSend = computed(() => Boolean(message.value.trim() || selectedFile.value) && !store.busy)

function selectFile(event: Event) {
  const target = event.target as HTMLInputElement
  selectedFile.value = target.files?.[0] ?? null
}

function clearSelectedFile() {
  selectedFile.value = null
  if (fileInput.value) fileInput.value.value = ''
}

async function send() {
  if (!canSend.value) return
  const currentMessage = message.value
  const currentFile = selectedFile.value
  message.value = ''
  clearSelectedFile()
  await store.startVerification(currentMessage, currentFile, executionMode.value).catch(() => undefined)
}

function onComposerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void send()
  }
}

function statusText(status: VerificationClaim['status']) {
  return { VERIFIED: '已验证', CONFLICT: '存在冲突', INSUFFICIENT: '证据不足' }[status]
}

function locatorText(locator: Record<string, unknown>) {
  if (locator.sheet && locator.cellRange) return `${locator.sheet}!${locator.cellRange}`
  if (locator.page) return `第 ${locator.page} 页`
  if (locator.slide) return `第 ${locator.slide} 张幻灯片`
  if (locator.paragraph) return `第 ${locator.paragraph} 段`
  if (locator.lineStart) return `第 ${locator.lineStart}–${locator.lineEnd ?? locator.lineStart} 行`
  return '原文位置已记录'
}

function eventText(type: string, data: Record<string, unknown>) {
  const labels: Record<string, string> = {
    RUN_CREATED: '运行条件已锁定',
    TEXT_DELTA: '模型正在分析材料',
    TOOL_STARTED: `调用证据工具 ${String(data.tool ?? '')}`,
    TOOL_ENDED: `证据工具返回 ${String(data.tool ?? '')}`,
    AGENT_RESULT: '已生成结构化核验结果',
    AGENT_ENDED: 'Agent 推理结束',
    RUN_COMPLETED: '正式结果已完成',
    RUN_FAILED: '正式运行失败',
    RUN_STATUS: `运行状态 ${String(data.status ?? '')}`,
  }
  return labels[type] ?? type
}

/**
 * 模型流式输出会为每个很小的文本片段产生 TEXT_DELTA。执行轨迹只需要表达“模型正在分析”这个阶段，
 * 因此把相邻增量折叠成一条；工具调用会自然切开不同推理阶段，仍能保留完整的业务顺序。
 */
function timelineEvents(conversation: VerificationConversation) {
  const events = conversation.events
  const compacted = events.filter((event, index) => (
    event.type !== 'TEXT_DELTA' || index === 0 || events[index - 1]?.type !== 'TEXT_DELTA'
  ))
  const terminalType = conversation.task?.status === 'COMPLETED'
    ? 'RUN_COMPLETED'
    : conversation.task?.status === 'FAILED' || conversation.task?.status === 'PARTIAL'
      ? 'RUN_FAILED'
      : null
  if (terminalType && !events.some((event) => event.type === terminalType)) {
    return [...compacted, { type: terminalType, data: { status: conversation.task?.status } }]
  }
  return compacted
}
</script>

<template>
  <section class="chat-page">
    <p class="eyebrow">VERIFY / CHAT</p>
    <div class="page-heading">
      <div>
        <h1>企业材料事实核验</h1>
        <p class="page-lead">粘贴材料文字或上传文件，选择通用基线或当前 Stable，实时查看 Agent 如何形成可追溯结论。</p>
      </div>
      <span class="stable-chip"><span></span>{{ executionMode === 'BASELINE' ? '通用基线' : '当前 Stable' }}</span>
    </div>

    <section class="panel chat-composer">
      <textarea
        v-model="message"
        data-test="chat-message"
        rows="4"
        maxlength="20000"
        placeholder="粘贴待核验的企业材料，或写下对附件的补充说明。Enter 发送，Shift+Enter 换行。"
        @keydown="onComposerKeydown"
      ></textarea>
      <div v-if="selectedFile" class="attachment-chip">
        <span>附件</span><strong>{{ selectedFile.name }}</strong>
        <button type="button" aria-label="移除附件" @click="clearSelectedFile">×</button>
      </div>
      <div class="composer-actions">
        <div class="mode-switch" aria-label="运行方式">
          <button :class="{ active: executionMode === 'BASELINE' }" @click="executionMode = 'BASELINE'">
            通用基线 <small>不加载 Skill</small>
          </button>
          <button :class="{ active: executionMode === 'STABLE' }" @click="executionMode = 'STABLE'">
            当前 Stable <small>正式 Skill</small>
          </button>
        </div>
        <label class="attachment-action">
          <input
            ref="fileInput"
            type="file"
            accept=".pdf,.doc,.docx,.ppt,.pptx,.md,.markdown,.txt,.xls,.xlsx,.csv"
            @change="selectFile"
          />
          ＋ 添加文件
        </label>
        <button data-test="start-verification" class="primary-action compact" :disabled="!canSend" @click="send">
          {{ store.busy ? '核验中…' : '发送核验' }}
        </button>
      </div>
      <p v-if="store.error" class="error-message">{{ store.error }}</p>
    </section>

    <section v-if="!conversations.length" class="conversation-empty">
      <strong>发送后会出现一张实时任务卡</strong>
      <span>01 输入快照</span><span>02 执行轨迹</span><span>03 核验主张</span>
    </section>

    <article v-for="conversation in conversations" :key="conversation.id" class="conversation-card">
      <header>
        <div>
          <small>{{ conversation.executionMode }}</small>
          <strong>{{ conversation.fileName ?? '纯文字材料' }}</strong>
        </div>
        <span class="gate-chip" :class="conversation.task?.status === 'COMPLETED' ? 'pass' : 'pending'">
          {{ conversation.task?.status ?? 'PREPARING' }}
        </span>
      </header>

      <section class="task-stage">
        <div class="stage-title"><span>01</span><strong>输入快照</strong></div>
        <div class="snapshot-grid">
          <div><small>INPUT</small><strong>{{ conversation.task?.inputType ?? (conversation.fileName ? 'FILE' : 'TEXT') }}</strong></div>
          <div><small>FILE</small><strong>{{ conversation.fileName ?? 'message.txt' }}</strong></div>
          <div class="wide"><small>DOCUMENT SNAPSHOT</small><code>{{ conversation.task?.documentSnapshotHash ?? '正在确定性解析' }}</code></div>
        </div>
        <p v-if="conversation.message" class="message-preview">{{ conversation.message }}</p>
      </section>

      <section class="task-stage">
        <div class="stage-title"><span>02</span><strong>执行轨迹</strong></div>
        <ol class="live-timeline">
          <li class="done"><i></i><span>材料已固定并生成 locator</span></li>
          <li v-for="(event, index) in timelineEvents(conversation)" :key="`${event.type}-${index}`">
            <i></i><span>{{ eventText(event.type, event.data) }}</span><code>{{ event.type }}</code>
          </li>
          <li v-if="!conversation.events.length"><i></i><span>等待 Agent 事件…</span></li>
        </ol>
      </section>

      <section class="task-stage">
        <div class="stage-title">
          <span>03</span><strong>核验主张</strong><small>{{ conversation.claims.length }} 条</small>
        </div>
        <div v-if="!conversation.claims.length" class="compact-empty">
          {{ conversation.error ?? '完成后逐条展示原文位置、标准化值与外部证据。' }}
        </div>
        <div v-for="claim in conversation.claims" :key="claim.id" class="claim-card compact-claim">
          <div class="claim-main">
            <span class="claim-status" :class="claim.status.toLowerCase()">{{ statusText(claim.status) }}</span>
            <h2>{{ claim.claimText }}</h2>
            <p>{{ claim.explanation }}</p>
            <div class="claim-meta">
              <span>原文 · {{ locatorText(claim.materialLocator) }}</span>
              <span v-if="claim.subject">主体 · {{ claim.subject.companyName }}</span>
            </div>
          </div>
          <div class="evidence-stack">
            <small>外部证据</small>
            <div v-for="evidence in claim.evidence" :key="String(evidence.recordId)" class="evidence-row">
              <strong>{{ evidence.dataset }}</strong><code>{{ evidence.recordId }}</code>
            </div>
            <div v-if="!claim.evidence.length" class="evidence-row muted">无可对齐外部证据</div>
          </div>
        </div>
      </section>
    </article>
  </section>
</template>
