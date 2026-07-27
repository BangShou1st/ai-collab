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

  it('switches to 5s interval after 30 seconds', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('document', { visibilityState: 'visible' })
    const load = vi.fn().mockResolvedValue(['DETAIL_GENERATING'])
    const poller = new PlanningPoller()

    poller.start(load)
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(1))
    // After 30s, interval should be 5s
    vi.advanceTimersByTime(30000)
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(2))
    // Next poll should be at 5s, not 2s
    vi.advanceTimersByTime(2000)
    expect(load).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(3000)
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(3))
  })

  it('pauses polling when document is hidden', async () => {
    vi.useFakeTimers()
    let visibility = 'visible'
    vi.stubGlobal('document', { get visibilityState() { return visibility } })
    const load = vi.fn().mockResolvedValue(['SKELETON_GENERATING'])
    const poller = new PlanningPoller()

    poller.start(load)
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(1))
    visibility = 'hidden'
    await vi.advanceTimersByTimeAsync(5000)
    // Should not have polled again while hidden
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('stops completely after stop()', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('document', { visibilityState: 'visible' })
    const load = vi.fn().mockResolvedValue(['SKELETON_GENERATING'])
    const poller = new PlanningPoller()

    poller.start(load)
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(1))
    poller.stop()
    await vi.advanceTimersByTimeAsync(10000)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('handles CONFIRMING as active status', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('document', { visibilityState: 'visible' })
    const load = vi.fn()
      .mockResolvedValueOnce(['CONFIRMING'])
      .mockResolvedValueOnce(['CONFIRMED'])
    const poller = new PlanningPoller()

    poller.start(load)
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(1))
    await vi.advanceTimersByTimeAsync(2000)
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('does not poll for non-active statuses', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('document', { visibilityState: 'visible' })
    const load = vi.fn().mockResolvedValue(['READY'])
    const poller = new PlanningPoller()

    poller.start(load)
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(1))
    await vi.advanceTimersByTimeAsync(10000)
    expect(load).toHaveBeenCalledTimes(1)
  })
})
