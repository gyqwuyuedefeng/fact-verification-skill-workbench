import { defineStore } from 'pinia'

import {
  createTask,
  getPrimaryClaims,
  getTask,
  openRunEventStream,
  startRun,
  uploadMaterial,
} from '../api/verification'
import type {
  RunBusinessEvent,
  VerificationClaim,
  VerificationConversation,
  VerificationTask,
} from '../types/verification'

function requestId(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`
}

export const useVerificationStore = defineStore('verification', {
  state: () => ({
    task: null as VerificationTask | null,
    claims: [] as VerificationClaim[],
    shadowClaims: [] as VerificationClaim[],
    events: [] as RunBusinessEvent[],
    conversations: [] as VerificationConversation[],
    busy: false,
    error: null as string | null,
    eventSource: null as EventSource | null,
  }),
  actions: {
    async startVerification(
      message: string,
      file: File | null,
      executionMode: 'BASELINE' | 'STABLE',
    ) {
      this.busy = true
      this.error = null
      this.claims = []
      this.shadowClaims = []
      this.events = []
      this.eventSource?.close()
      const conversation: VerificationConversation = {
        id: crypto.randomUUID(),
        message: message.trim(),
        fileName: file?.name ?? null,
        executionMode,
        task: null,
        claims: [],
        events: [],
        error: null,
      }
      this.conversations.unshift(conversation)
      try {
        this.task = await createTask(requestId('task'))
        conversation.task = this.task
        this.task = await uploadMaterial(this.task.id, requestId('upload'), file, message)
        conversation.task = this.task
        this.task = await startRun(this.task.id, requestId('run'), executionMode)
        conversation.task = this.task
        if (this.task.primaryRunId) {
          this.eventSource = openRunEventStream(this.task.primaryRunId, (type, data) => {
            const event = { type, data }
            this.events.push(event)
            conversation.events.push(event)
          })
        }
        await this.pollUntilTerminal(conversation)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '核验失败'
        conversation.error = this.error
        throw error
      } finally {
        this.busy = false
      }
    },
    async pollUntilTerminal(conversation: VerificationConversation) {
      if (!conversation.task) return
      for (let attempt = 0; attempt < 240; attempt += 1) {
        const task = await getTask(conversation.task.id)
        this.task = task
        conversation.task = task
        if (task.status === 'COMPLETED') {
          const claims = await getPrimaryClaims(task.id)
          this.claims = claims
          conversation.claims = claims
          this.eventSource?.close()
          return
        }
        if (task.status === 'FAILED' || task.status === 'PARTIAL') {
          this.eventSource?.close()
          conversation.error = task.errorCode ?? '核验未完成'
          return
        }
        await new Promise((resolve) => setTimeout(resolve, 750))
      }
      throw new Error('核验运行超时，请稍后从历史任务查看')
    },
  },
})
