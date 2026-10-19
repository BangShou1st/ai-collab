import { describe, expect, it } from 'vitest'
import {
  AgentSseDiagnosticTracker,
  classifySseDisconnect,
  describeAgentSseDisconnect,
  formatAgentSseDiagnostic,
  type AgentSseDiagnostic,
} from './agent-sse-diagnostics'

describe('SSE 断线归类', () => {
  it('本地取消（signal 已中止）归类为 CLIENT_ABORT', () => {
    expect(classifySseDisconnect(new Error('boom'), true)).toEqual({ category: 'CLIENT_ABORT' })
  })

  it('AbortError 归类为 CLIENT_ABORT', () => {
    const abort = Object.assign(new Error('aborted'), { name: 'AbortError' })
    expect(classifySseDisconnect(abort, false)).toEqual({ category: 'CLIENT_ABORT' })
  })

  it('HTTP 非 2xx 归类为 HTTP_ERROR 并保留状态码', () => {
    expect(classifySseDisconnect(new Error('SSE 503'), false)).toEqual({ category: 'HTTP_ERROR', httpStatus: 503 })
  })

  it('TypeError 归类为 NETWORK', () => {
    expect(classifySseDisconnect(new TypeError('Failed to fetch'), false)).toEqual({ category: 'NETWORK' })
  })

  it('未知错误与非法输入归类为 UNKNOWN', () => {
    expect(classifySseDisconnect(new Error('weird'), false)).toEqual({ category: 'UNKNOWN' })
    expect(classifySseDisconnect('oops', false)).toEqual({ category: 'UNKNOWN' })
    expect(classifySseDisconnect(undefined, false)).toEqual({ category: 'UNKNOWN' })
  })
})

describe('SSE 断线诊断跟踪器', () => {
  it('正常连接不产生断线记录，只标记已连接', () => {
    const tracker = new AgentSseDiagnosticTracker()
    expect(tracker.opened('run-1', 3)).toBeNull()
    expect(tracker.snapshot()).toMatchObject({ category: null, attempts: 0, recovered: false })
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
    expect(tracker.snapshot()).toMatchObject({ category: 'NETWORK', attempts: 3, recovered: false })
  })

  it('恢复后产出一条 recovered 记录，并复位区间', () => {
    const tracker = new AgentSseDiagnosticTracker()
    tracker.disconnected('run-1', new Error('SSE 500'), false, 12)
    tracker.disconnected('run-1', new Error('SSE 500'), false, 12)
    const recovered = tracker.opened('run-1', 12)
    expect(recovered).toEqual({
      scope: 'agent.sse', phase: 'recovered', category: 'HTTP_ERROR', runId: 'run-1', lastSequence: 12, attempt: 2,
    })
    expect(tracker.snapshot()).toEqual({ category: null, lastSequence: 12, attempts: 0, recovered: true })
  })

  it('运行未终结却收到流正常结束归类为 STREAM_ENDED', () => {
    const tracker = new AgentSseDiagnosticTracker()
    const record = tracker.endedUnexpectedly('run-1', 8)
    expect(record).toEqual({
      scope: 'agent.sse', phase: 'disconnect', category: 'STREAM_ENDED', runId: 'run-1', lastSequence: 8, attempt: 1,
    })
    // 同一区间后续结束不再重复记录
    expect(tracker.endedUnexpectedly('run-1', 8)).toBeNull()
    // 恢复后按 STREAM_ENDED 类别产出 recovered 并复位
    expect(tracker.opened('run-1', 8)).toMatchObject({ phase: 'recovered', category: 'STREAM_ENDED' })
  })

  it('reset 清空区间，避免跨运行串用陈旧断线信息', () => {
    const tracker = new AgentSseDiagnosticTracker()
    tracker.disconnected('run-1', new TypeError('net'), false, 4)
    tracker.reset()
    expect(tracker.snapshot()).toEqual({ category: null, lastSequence: 0, attempts: 0, recovered: false })
    expect(tracker.opened('run-2', 0)).toBeNull()
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
