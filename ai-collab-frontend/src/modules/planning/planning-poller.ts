import type { PlanStatus } from './types'

const ACTIVE = new Set<PlanStatus>(['SKELETON_GENERATING', 'DETAIL_GENERATING', 'CONFIRMING'])

export class PlanningPoller {
  private timer: ReturnType<typeof setTimeout> | null = null
  private started = 0
  private generation = 0

  start(load: () => Promise<PlanStatus[]>): void {
    this.stop(); this.started = Date.now(); const token = ++this.generation
    const tick = async () => {
      if (token !== this.generation) return
      if (document.visibilityState === 'hidden') { this.schedule(tick, 2000); return }
      const statuses = await load()
      if (token === this.generation && statuses.some(status => ACTIVE.has(status))) {
        this.schedule(tick, Date.now() - this.started < 30000 ? 2000 : 5000)
      }
    }
    void tick()
  }

  stop(): void {
    this.generation++
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
  }

  private schedule(callback: () => void, delay: number): void {
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(callback, delay)
  }
}
