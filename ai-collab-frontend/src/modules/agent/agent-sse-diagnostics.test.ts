import { describe, expect, it } from 'vitest'
import {
  AgentSseDiagnosticTracker,
  classifySseDisconnect,
  describeAgentSseDisconnect,
  formatAgentSseDiagnostic,
  type AgentSseDiagnostic,
} from './agent-sse-diagnostics'

describe('SSE 断线归类', () => {
  it('只有显式本地取消证据（AbortController 已中止）才判定 CLIENT_ABORT', () => {
    expect(classifySseDisconnect(new Error('boom'), true)).toEqual({ category: 'CLIENT_ABORT' })
  })

  it('AbortError 缺少显式本地取消证据时保持 UNKNOWN', () => {
    const abort = Object.assign(new Error('aborted'), { name: 'AbortError' })
    expect(classifySseDisconnect(abort, false)).toEqual({ category: 'UNKNOWN' })
    expect(classifySseDisconnect(abort, true)).toEqual({ category: 'CLIENT_ABORT' })
    expect(classifySseDisconnect(new DOMException('aborted', 'AbortError'), false)).toEqual({ category: 'UNKNOWN' })
  })

  it('HTTP 非 2xx 归类为 HTTP_ERROR 并保留状态码', () => {
    expect(classifySseDisconnect(new Error('SSE 503'), false)).toEqual({ category: 'HTTP_ERROR', httpStatus: 503 })
  })

  it('TypeError 归类为 NETWORK', () => {
    expect(classifySseDisconnect(new TypeError('Failed to fetch'), false)).toEqual({ category: 'NETWORK' })
  })

  it('缺少本地取消证据时不判定 CLIENT_ABORT（仅网络层 ERR_ABORTED 不构成证据）', () => {
    // 浏览器网络层的 ERR_ABORTED 标签不是本地取消证据：证据不足时只能落到 NETWORK 或 UNKNOWN，
    // 绝不因为"没有诊断记录 + ERR_ABORTED"就判定为客户端主动取消
    expect(classifySseDisconnect(new Error('net::ERR_ABORTED'), false)).toEqual({ category: 'UNKNOWN' })
    expect(classifySseDisconnect(new TypeError('Failed to fetch'), false)).toEqual({ category: 'NETWORK' })
  })

  it('未知错误与非法输入归类为 UNKNOWN', () => {
    expect(classifySseDisconnect(new Error('weird'), false)).toEqual({ category: 'UNKNOWN' })
    expect(classifySseDisconnect('oops', false)).toEqual({ category: 'UNKNOWN' })
    expect(classifySseDisconnect(undefined, false)).toEqual({ category: 'UNKNOWN' })
  })
})

describe('SSE 断线诊断跟踪器', () => {
  it('正常连接只标记 CONNECTED，不产生断线记录', () => {
    const tracker = new AgentSseDiagnosticTracker()
    expect(tracker.opened('run-1', 3)).toBeNull()
    expect(tracker.snapshot()).toMatchObject({ state: 'CONNECTED', category: null, attempts: 0, recovered: false })
  })

  it('一次连续断线只产出一条记录，重连尝试次数递增', () => {
    const tracker = new AgentSseDiagnosticTracker()
    const first = tracker.disconnected('run-1', new TypeError('net'), false, 7)
    expect(first).toEqual({
      scope: 'agent.sse', phase: 'disconnect', category: 'NETWORK', runId: 'run-1', lastSequence: 7, attempt: 1,
    })
    // 同一断线区间内的后续失败不再重复记录，但尝试次数继续累计
    expect(tracker.disconnected('run-1', new TypeError('net'), false, 7)).toBeNull()
    expect(tracker.disconnected('run-1', new TypeError('net'), false, 7)).toBeNull()
    expect(tracker.snapshot()).toMatchObject({ state: 'RECONNECTING', category: 'NETWORK', attempts: 3, recovered: false })
  })

  it('仅重新拿到 HTTP 200 只记为"传输已重连"，不等同于完整事件恢复', () => {
    const tracker = new AgentSseDiagnosticTracker()
    tracker.disconnected('run-1', new Error('SSE 500'), false, 12)
    tracker.disconnected('run-1', new Error('SSE 500'), false, 12)
    const reconnected = tracker.opened('run-1', 12)
    expect(reconnected).toEqual({
      scope: 'agent.sse', phase: 'transport_reconnected', category: 'HTTP_ERROR', runId: 'run-1', lastSequence: 12, attempt: 2,
    })
    // 传输已重连但事件尚未对齐：不得标记为"已恢复"
    expect(tracker.snapshot()).toMatchObject({ state: 'TRANSPORT_RECONNECTED', category: 'HTTP_ERROR', recovered: false })
  })

  it('持久事件对齐后才产出 recovered，并把区间收口为 RECOVERED', () => {
    const tracker = new AgentSseDiagnosticTracker()
    tracker.disconnected('run-1', new Error('SSE 500'), false, 12)
    tracker.disconnected('run-1', new Error('SSE 500'), false, 12)
    tracker.opened('run-1', 12)
    const recovered = tracker.aligned()
    expect(recovered).toEqual({
      scope: 'agent.sse', phase: 'recovered', category: 'HTTP_ERROR', runId: 'run-1', lastSequence: 12, attempt: 2,
    })
    // 恢复后保留类别供"已恢复"展示，尝试次数复位
    expect(tracker.snapshot()).toEqual({
      state: 'RECOVERED', category: 'HTTP_ERROR', lastSequence: 12, attempts: 0, recovered: true,
    })
    // 已恢复后再次对齐不重复产出
    expect(tracker.aligned()).toBeNull()
  })

  it.each(['error', 'end'] as const)('传输重连但未对齐时再次断开（%s）回到重连中，且不重复区间记录', failure => {
    const tracker = new AgentSseDiagnosticTracker()
    expect(tracker.disconnected('run-1', new TypeError('net'), false, 7)).toMatchObject({ phase: 'disconnect' })
    tracker.opened('run-1', 7)
    const record = failure === 'error'
      ? tracker.disconnected('run-1', new TypeError('net'), false, 7)
      : tracker.endedUnexpectedly('run-1', 7)
    expect(record).toBeNull()
    expect(tracker.snapshot()).toMatchObject({ state: 'RECONNECTING', category: 'NETWORK', attempts: 2, recovered: false })
    expect(tracker.aligned()).toBeNull()
    expect(tracker.opened('run-1', 7)).toMatchObject({ phase: 'transport_reconnected', attempt: 2 })
    expect(tracker.aligned()).toMatchObject({ phase: 'recovered', attempt: 2 })
    expect(tracker.aligned()).toBeNull()
    expect(tracker.snapshot()).toMatchObject({ state: 'RECOVERED', attempts: 0, recovered: true })
  })

  it('未进入"传输已重连"状态时不对齐，避免把普通连接误标为恢复', () => {
    const tracker = new AgentSseDiagnosticTracker()
    expect(tracker.aligned()).toBeNull() // 初始 UNKNOWN
    tracker.opened('run-1', 1)
    expect(tracker.aligned()).toBeNull() // 普通 CONNECTED
    tracker.disconnected('run-1', new TypeError('net'), false, 1)
    expect(tracker.aligned()).toBeNull() // 仍 RECONNECTING，尚未重新建立传输
  })

  it('恢复后同一运行的新断线能重新记录（区间不跨断线复用）', () => {
    const tracker = new AgentSseDiagnosticTracker()
    tracker.disconnected('run-1', new TypeError('net'), false, 5)
    tracker.opened('run-1', 5)
    tracker.aligned()
    // 恢复后再次断线：重新产出 disconnect 记录，尝试次数从 1 重新计数
    const again = tracker.disconnected('run-1', new TypeError('net'), false, 20)
    expect(again).toEqual({
      scope: 'agent.sse', phase: 'disconnect', category: 'NETWORK', runId: 'run-1', lastSequence: 20, attempt: 1,
    })
    expect(tracker.snapshot()).toMatchObject({ state: 'RECONNECTING', attempts: 1, recovered: false })
  })

  it('运行未终结却收到流正常结束归类为 STREAM_ENDED', () => {
    const tracker = new AgentSseDiagnosticTracker()
    const record = tracker.endedUnexpectedly('run-1', 8)
    expect(record).toEqual({
      scope: 'agent.sse', phase: 'disconnect', category: 'STREAM_ENDED', runId: 'run-1', lastSequence: 8, attempt: 1,
    })
    // 同一区间后续结束不再重复记录
    expect(tracker.endedUnexpectedly('run-1', 8)).toBeNull()
    // 重新建立只记"传输已重连"，事件对齐后才按 STREAM_ENDED 类别收口为 recovered
    expect(tracker.opened('run-1', 8)).toMatchObject({ phase: 'transport_reconnected', category: 'STREAM_ENDED' })
    expect(tracker.aligned()).toMatchObject({ phase: 'recovered', category: 'STREAM_ENDED' })
  })

  it('reset 清空区间与状态，避免跨运行串用陈旧断线信息', () => {
    const tracker = new AgentSseDiagnosticTracker()
    tracker.disconnected('run-1', new TypeError('net'), false, 4)
    tracker.reset()
    expect(tracker.snapshot()).toEqual({
      state: 'UNKNOWN', category: null, lastSequence: 0, attempts: 0, recovered: false,
    })
    expect(tracker.opened('run-2', 0)).toBeNull()
    expect(tracker.snapshot()).toMatchObject({ state: 'CONNECTED' })
  })
})

describe('SSE 诊断记录脱敏输出', () => {
  it('序列化只包含契约字段，不含正文、令牌或凭据', () => {
    const record: AgentSseDiagnostic = {
      scope: 'agent.sse', phase: 'disconnect', category: 'NETWORK',
      runId: 'run-1', lastSequence: 9, attempt: 1,
    }
    const parsed = JSON.parse(formatAgentSseDiagnostic(record)) as Record<string, unknown>
    expect(Object.keys(parsed).sort()).toEqual(
      ['attempt', 'category', 'lastSequence', 'phase', 'runId', 'scope'],
    )
  })

  it('HTTP 类别记录仅附加非敏感状态码', () => {
    const record: AgentSseDiagnostic = {
      scope: 'agent.sse', phase: 'disconnect', category: 'HTTP_ERROR',
      runId: 'run-1', lastSequence: 2, attempt: 1, httpStatus: 503,
    }
    const parsed = JSON.parse(formatAgentSseDiagnostic(record)) as Record<string, unknown>
    expect(parsed.httpStatus).toBe(503)
    expect(Object.keys(parsed).sort()).toEqual(
      ['attempt', 'category', 'httpStatus', 'lastSequence', 'phase', 'runId', 'scope'],
    )
  })

  it('类别映射为脱敏中文说明', () => {
    expect(describeAgentSseDisconnect('CLIENT_ABORT')).toBe('本地取消')
    expect(describeAgentSseDisconnect('HTTP_ERROR')).toBe('服务端响应')
    expect(describeAgentSseDisconnect('NETWORK')).toBe('传输中断')
    expect(describeAgentSseDisconnect('STREAM_ENDED')).toBe('流提前结束')
    expect(describeAgentSseDisconnect('UNKNOWN')).toBe('未归类')
  })
})
