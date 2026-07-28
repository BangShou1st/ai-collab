import type { PlanStatus } from './types'

const ACTIVE = new Set<PlanStatus>(['SKELETON_GENERATING', 'DETAIL_GENERATING', 'REPAIRING', 'CONFIRMING'])
const RETRYABLE_ERRORS = new Set([429, 502, 503, 504])
const STOP_ERRORS = new Set([401, 403, 404])

interface PollerOptions {
  load: () => Promise<PlanStatus[]>
  onError?: (error: unknown) => void
}

export class PlanningPoller {
  private timer: ReturnType<typeof setTimeout> | null = null
  private started = 0
  private generation = 0

  start(options: PollerOptions): void {
    this.stop(); this.started = Date.now(); const token = ++this.generation
    const tick = async () => {
      if (token !== this.generation) return
      if (document.visibilityState === 'hidden') { this.schedule(tick, 2000); return }
      try {
        const statuses = await options.load()
        if (token === this.generation && statuses.some(status => ACTIVE.has(status))) {
          this.schedule(tick, Date.now() - this.started < 30000 ? 2000 : 5000)
        }
      } catch (error) {
        options.onError?.(error)
        if (this.shouldStop(error)) return
        this.schedule(tick, this.getRetryDelay(error))
      }
    }
    void tick()
  }

  stop(): void {
    this.generation++
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
  }

  private shouldStop(error: unknown): boolean {
    if (!error || typeof error !== 'object') return false
    const axiosError = error as { response?: { status?: number } }
    const status = axiosError.response?.status
    return status !== undefined && STOP_ERRORS.has(status)
  }

  private getRetryDelay(error: unknown): number {
    if (!error || typeof error !== 'object') return 5000
    const axiosError = error as { response?: { status?: number } }
    const status = axiosError.response?.status
    if (status !== undefined && RETRYABLE_ERRORS.has(status)) {
      return Math.min(30000, 2000 * Math.pow(2, Math.floor(Math.random() * 3)))
    }
    return 5000
  }

  private schedule(callback: () => void, delay: number): void {
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(callback, delay)
  }
}
