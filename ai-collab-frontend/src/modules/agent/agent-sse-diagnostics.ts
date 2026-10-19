/**
 * Agent SSE 断线诊断（脱敏）。
 *
 * 只记录可归因所需的**非敏感**字段：断线类别、最后已确认事件序号、运行 ID 关联与恢复结果。
 * 绝不记录请求/响应正文、Header、Cookie、Token、API Key 或文档内容——因此可以把这些记录
 * 安全地用于排查历史 `ERR_ABORTED`，而不复制项目既有的证据脱敏约束之外的任何数据。
 */

export type AgentSseDisconnectCategory =
  /** 本地主动取消：切换会话/项目、重开运行、暂停、组件卸载。 */
  | 'CLIENT_ABORT'
  /** 服务端返回非 2xx（状态码本身不含敏感信息）。 */
  | 'HTTP_ERROR'
  /** 传输中断：fetch 失败或流读取抛错。 */
  | 'NETWORK'
  /** 运行未进入终态却收到流正常结束（服务端/代理提前关闭连接）。 */
  | 'STREAM_ENDED'
  /** 无法归类，保留原始归类缺口而不是猜测成因。 */
  | 'UNKNOWN'

export interface AgentSseDiagnostic {
  scope: 'agent.sse'
  phase: 'disconnect' | 'recovered'
  category: AgentSseDisconnectCategory
  /** 运行 ID 关联：仅用于把记录挂到对应运行，不含用户身份或文档信息。 */
  runId: string
  /** 断线前最后被接受（实际应用）的持久事件序号；恢复时用于核对续接位置。 */
  lastSequence: number
  /** 本次断线后第几次重连尝试（从 1 开始）。 */
  attempt: number
  /** 仅 HTTP 错误类别携带；非敏感状态码。 */
  httpStatus?: number
}

export interface AgentSseDiagnosticSnapshot {
  category: AgentSseDisconnectCategory | null
  lastSequence: number
  attempts: number
  recovered: boolean
}

const HTTP_STATUS_PATTERN = /^SSE (\d{3})$/

/** 把一次流失败归类为脱敏类别；不读取任何正文或凭据。 */
export function classifySseDisconnect(
  reason: unknown,
  aborted: boolean,
): { category: AgentSseDisconnectCategory; httpStatus?: number } {
  if (aborted) return { category: 'CLIENT_ABORT' }
  if (reason instanceof Error) {
    if (reason.name === 'AbortError') return { category: 'CLIENT_ABORT' }
    const match = HTTP_STATUS_PATTERN.exec(reason.message)
    if (match) return { category: 'HTTP_ERROR', httpStatus: Number(match[1]) }
    if (reason instanceof TypeError) return { category: 'NETWORK' }
    return { category: 'UNKNOWN' }
  }
  return { category: 'UNKNOWN' }
}

/**
 * 按"断线区间"收敛记录：一次连续断线只发一条 `disconnect`（避免后端不可用时刷屏），
 * 恢复后发一条 `recovered` 并复位。返回的可选记录由调用方决定是否输出。
 */
export class AgentSseDiagnosticTracker {
  private category: AgentSseDisconnectCategory | null = null
  private lastSequence = 0
  private attempts = 0
  private recovered = false
  private runId = ''

  /** 连接建立：若此前处于断线区间则产出一条 `recovered` 记录。 */
  opened(runId: string, lastSequence: number): AgentSseDiagnostic | null {
    this.runId = runId
    this.lastSequence = lastSequence
    if (this.category === null) return null
    const category = this.category
    const attempts = this.attempts
    this.recovered = true
    const record: AgentSseDiagnostic = {
      scope: 'agent.sse',
      phase: 'recovered',
      category,
      runId,
      lastSequence,
      attempt: attempts,
    }
    this.category = null
    this.attempts = 0
    return record
  }

  /** 流失败：本断线区间内只在首次失败时产出一条 `disconnect`。 */
  disconnected(runId: string, reason: unknown, aborted: boolean, lastSequence: number): AgentSseDiagnostic | null {
    const classified = classifySseDisconnect(reason, aborted)
    return this.beginEpisode(classified.category, runId, lastSequence, classified.httpStatus)
  }

  /** 运行未终结却收到流正常结束：归类为 `STREAM_ENDED`，同样按断线区间收敛。 */
  endedUnexpectedly(runId: string, lastSequence: number): AgentSseDiagnostic | null {
    return this.beginEpisode('STREAM_ENDED', runId, lastSequence)
  }

  private beginEpisode(
    category: AgentSseDisconnectCategory,
    runId: string,
    lastSequence: number,
    httpStatus?: number,
  ): AgentSseDiagnostic | null {
    this.runId = runId
    this.lastSequence = lastSequence
    this.recovered = false
    this.attempts += 1
    if (this.category !== null) return null
    this.category = category
    return {
      scope: 'agent.sse',
      phase: 'disconnect',
      category,
      runId,
      lastSequence,
      attempt: this.attempts,
      ...(httpStatus === undefined ? {} : { httpStatus }),
    }
  }

  /** 结束/切换作用域：复位区间，避免跨运行的陈旧断线信息串到新运行展示。 */
  reset(): void {
    this.category = null
    this.lastSequence = 0
    this.attempts = 0
    this.recovered = false
    this.runId = ''
  }

  snapshot(): AgentSseDiagnosticSnapshot {
    return {
      category: this.category,
      lastSequence: this.lastSequence,
      attempts: this.attempts,
      recovered: this.recovered,
    }
  }
}

const CATEGORY_LABEL: Record<AgentSseDisconnectCategory, string> = {
  CLIENT_ABORT: '本地取消',
  HTTP_ERROR: '服务端响应',
  NETWORK: '传输中断',
  STREAM_ENDED: '流提前结束',
  UNKNOWN: '未归类',
}

/** 面向 UI 的脱敏中文说明，不展示原始错误对象或运行 ID。 */
export function describeAgentSseDisconnect(category: AgentSseDisconnectCategory): string {
  return CATEGORY_LABEL[category]
}

/** 序列化脱敏诊断记录；字段由 {@link AgentSseDiagnostic} 契约限定，不含正文/令牌/凭据。 */
export function formatAgentSseDiagnostic(record: AgentSseDiagnostic): string {
  return JSON.stringify(record)
}

/** 输出脱敏诊断记录；仅用于日志/浏览器控制台取证。 */
export function emitAgentSseDiagnostic(record: AgentSseDiagnostic): void {
  console.info('[agent-sse]', formatAgentSseDiagnostic(record))
}
