import { describe, expect, it } from 'vitest'

import { metricLabel, shortId, skillVersionLabel, statusLabel } from '../labels'

describe('评测展示标签', () => {
  it('保留中文含义和英文状态原值', () => {
    const candidate = {
      id: 'candidate-version-12345678',
      skillKey: 'fact-verification',
      version: 'v2',
      parentVersionId: null,
      status: 'CANDIDATE' as const,
      contentHash: null,
      changeSummary: '候选修改',
      createdAt: '2026-08-13T00:00:00Z',
      frozenAt: '2026-08-13T00:00:00Z',
    }

    expect(statusLabel('RUNNING')).toBe('运行中（RUNNING）')
    expect(metricLabel('humanInterventionRate')).toBe('人工介入率（humanInterventionRate）')
    expect(skillVersionLabel(candidate)).toContain('候选版（CANDIDATE）')
    expect(statusLabel('VERIFIED')).toBe('已核验（VERIFIED）')
    expect(statusLabel('CONFLICT')).toBe('存在冲突（CONFLICT）')
    expect(statusLabel('INSUFFICIENT')).toBe('证据不足（INSUFFICIENT）')
  })

  it('未知标签和长 ID 不会静默丢失原值', () => {
    expect(statusLabel('WAITING_REVIEW')).toBe('未知状态（WAITING_REVIEW）')
    expect(metricLabel('customMetric')).toBe('未知指标（customMetric）')
    expect(shortId('evaluation-1234567890')).toBe('evaluati')
  })
})
