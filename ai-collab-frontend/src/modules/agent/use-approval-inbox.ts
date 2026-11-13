import { onScopeDispose, watch } from 'vue'
import { agentApi } from './agent-api'
import type { AgentApproval, AgentRunStatus } from './types'

export const APPROVAL_INBOX_INTERVAL_MS = 5_000

const TERMINAL_RUN_STATUSES = new Set<AgentRunStatus>([
  'SUCCEEDED', 'FAILED', 'FAILED_RETRYABLE', 'CANCELED', 'BUDGET_EXCEEDED',
])

/** 终态 Run 仍有待审批时才需要收件箱轮询。 */
export function shouldPollApprovalInbox(
  status: AgentRunStatus | null | undefined,
  approvals: readonly AgentApproval[],
): boolean {
  return Boolean(status && TERMINAL_RUN_STATUSES.has(status)
    && approvals.some(item => item.status === 'PENDING'))
}

export interface ApprovalInboxOptions {
  projectId: () => string
  runId: () => string | null
  enabled: () => boolean
  approvals: () => readonly AgentApproval[]
  merge: (items: AgentApproval[]) => void
  onError?: (reason: unknown) => void
  intervalMs?: number
}

/**
 * 终态 Run 的审批收件箱轮询。
 *
 * 只在调用方给定的 enabled 条件成立时运行；通过 updatedSince + ETag 拉取增量，
 * 后台标签暂停，focus/visibilitychange 立即补一次，scope 销毁时清理全部资源。
 */
export function useApprovalInbox(options: ApprovalInboxOptions) {
  const intervalMs = options.intervalMs ?? APPROVAL_INBOX_INTERVAL_MS
  let timer: number | undefined
  let inFlight: Promise<void> | null = null
  let etag: string | null = null
  let watermark: string | null = null
  let scopeKey = ''
  let disposed = false

  const hasDocument = () => typeof document !== 'undefined'
  const isVisible = () => !hasDocument() || document.visibilityState !== 'hidden'

  function stopTimer(): void {
    if (timer !== undefined) {
      window.clearTimeout(timer)
      timer = undefined
    }
  }

  function later(a: string | null, b: string | null): string | null {
    if (!a) return b
    if (!b) return a
    return Date.parse(b) > Date.parse(a) ? b : a
  }

  function seedWatermark(): void {
    watermark = options.approvals()
      .filter(item => item.runId === options.runId())
      .reduce<string | null>((latest, item) => later(latest, item.updatedAt), null)
  }

  function applyChanges(items: AgentApproval[]): void {
    const runId = options.runId()
    if (!runId || items.length === 0) return
    const current = new Map(options.approvals().map(item => [item.id, item]))
    const changed = items.filter(item => item.runId === runId && (() => {
      const previous = current.get(item.id)
      return !previous
        || previous.status !== item.status
        || previous.revision !== item.revision
        || previous.updatedAt !== item.updatedAt
    })())
    if (changed.length > 0) options.merge(changed)
  }

  function schedule(): void {
    stopTimer()
    if (disposed || !options.enabled() || !isVisible()) return
    timer = window.setTimeout(() => {
      timer = undefined
      void refreshNow()
    }, intervalMs)
  }

  function refreshNow(force = false): Promise<void> {
    if (disposed || !options.enabled() || !isVisible()) {
      schedule()
      return Promise.resolve()
    }
    if (inFlight) return inFlight
    const projectId = options.projectId()
    const runId = options.runId()
    if (!projectId || !runId) {
      schedule()
      return Promise.resolve()
    }
    const requestedEtag = force ? null : etag
    const updatedSince = force ? null : watermark
    inFlight = (async () => {
      try {
        const result = await agentApi.approvalInbox(
          projectId,
          { updatedSince },
          requestedEtag,
        )
        if (disposed) return
        if (!result.notModified) {
          etag = result.etag
          watermark = later(watermark, result.maxUpdatedAt)
          applyChanges(result.items)
        }
      } catch (reason) {
        if (!disposed) options.onError?.(reason)
      } finally {
        inFlight = null
        schedule()
      }
    })()
    return inFlight
  }

  function reconcile(): void {
    if (disposed) return
    const nextScope = `${options.projectId()}:${options.runId() ?? ''}`
    if (nextScope !== scopeKey) {
      scopeKey = nextScope
      etag = null
      watermark = null
      seedWatermark()
    }
    if (!options.enabled() || !isVisible()) {
      stopTimer()
      return
    }
    if (watermark === null) seedWatermark()
    void refreshNow()
    schedule()
  }

  function handleVisibilityChange(): void {
    if (!options.enabled()) {
      stopTimer()
      return
    }
    if (!isVisible()) {
      stopTimer()
      return
    }
    void refreshNow()
    schedule()
  }

  function handleFocus(): void {
    if (!options.enabled() || !isVisible()) return
    void refreshNow()
    schedule()
  }

  if (hasDocument()) {
    document.addEventListener('visibilitychange', handleVisibilityChange)
    window.addEventListener('focus', handleFocus)
  }
  watch(
    [options.projectId, options.runId, options.enabled],
    reconcile,
    { immediate: true },
  )

  function stop(): void {
    disposed = true
    stopTimer()
    if (hasDocument()) {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      window.removeEventListener('focus', handleFocus)
    }
  }

  onScopeDispose(stop)

  return { refreshNow, stop }
}
