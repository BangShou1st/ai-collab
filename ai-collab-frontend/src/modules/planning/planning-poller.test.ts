import { afterEach, describe, expect, it, vi } from 'vitest'
import { PlanningPoller } from './planning-poller'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('PlanningPoller', () => {
  it('polls active plans and stops after a stable result', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('document', { visibilityState: 'visible' })
    const load = vi.fn()
      .mockResolvedValueOnce(['SKELETON_GENERATING'])
      .mockResolvedValueOnce(['READY'])
    const poller = new PlanningPoller()

    poller.start(load)
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(1))
    await vi.advanceTimersByTimeAsync(2000)
    expect(load).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(10000)
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('invalidates an older project polling generation', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('document', { visibilityState: 'visible' })
    const first = vi.fn().mockResolvedValue(['SKELETON_GENERATING'])
    const second = vi.fn().mockResolvedValue(['READY'])
    const poller = new PlanningPoller()
    poller.start(first)
    await vi.waitFor(() => expect(first).toHaveBeenCalledTimes(1))
    poller.start(second)
    await vi.waitFor(() => expect(second).toHaveBeenCalledTimes(1))
    await vi.advanceTimersByTimeAsync(5000)
    expect(first).toHaveBeenCalledTimes(1)
  })
})
