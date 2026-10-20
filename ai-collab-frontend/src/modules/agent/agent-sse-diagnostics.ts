/**
 * Agent SSE 断线诊断（脱敏）。
 *
 * 只记录可归因所需的**非敏感**字段：断线类别、最后已确认事件序号、运行 ID 关联与恢复结果。
 * 绝不记录请求/响应正文、Header、Cookie、Token、API Key 或文档内容——因此可以把这些记录
 * 安全地用于排查历史 `ERR_ABORTED`，而不复制项目既有的证据脱敏约束之外的任何数据。
 *
 * 归因规则（P3-OBS-02 修正）：
 * - **缺少诊断记录本身不是证据**。"没有记录 + 浏览器网络层 ERR_ABORTED"不得直接判定为客户端主动取消。
 * - 只有存在显式的本地取消证据（本端 AbortController 被中止 / 页面生命周期 / 用户操作）时，
 *   才可判定 `CLIENT_ABORT`；证据不足的其余情形一律标记 `UNKNOWN`。
 * - 历史 `ERR_ABORTED` 因缺少逐事件证据，保持**未归因**，不因本规则被翻案。
 */

export type AgentSseDisconnectCategory =
  /** 本地主动取消：仅在持有显式本地证据（AbortController 被中止、页面生命周期、用户操作）时可用。 */
  | 'CLIENT_ABORT'
  /** 服务端返回非 2xx（状态码本身不含敏感信息）。 */
  | 'HTTP_ERROR'
  /** 传输中断：fetch 失败或流读取抛错。 */
  | 'NETWORK'
  /** 运行未进入终态却收到流正常结束（服务端/代理提前关闭连接）。 */
  | 'STREAM_ENDED'
  /** 无法归类，保留原始归类缺口而不是猜测成因。 */
  | 'UNKNOWN'

/**
 * 最小连接/恢复状态模型。
 * 区分"传输已连接"与"运行状态/持久事件已对齐"——仅收到 HTTP 200 不等于完整事件恢复。
 */
export type AgentSseConnectionState =
  /** 传输已连接，且当前不处于断线区间。 */
  | 'CONNECTED'
  /** 传输已断开，正在重试。 */
  | 'RECONNECTING'
  /** HTTP/SSE 传输重新建立，但尚未确认持久事件或运行状态已对齐。 */
  | 'TRANSPORT_RECONNECTED'
  /** 运行状态或持久事件已成功对齐，断线区间真正恢复。 */
  | 'RECOVERED'
  /** 证据不足、无法判定（如尚未连接，或缺少本地取消证据）。 */
  | 'UNKNOWN'

export type AgentSseDiagnosticPhase = 'disconnect' | 'transport_reconnected' | 'recovered'

export interface AgentSseDiagnostic {
  scope: 'agent.sse'
  phase: AgentSseDiagnosticPhase
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
  /** 当前连接/恢复状态（见 {@link AgentSseConnectionState}）。 */
  state: AgentSseConnectionState
  /** 最近一次断线区间的类别；未发生断线或已 reset 时为 null（保留用于"已恢复"展示）。 */
  category: AgentSseDisconnectCategory | null
  /** 最近一次断线前最后被接受的事件序号。 */
  lastSequence: number
  /** 当前断线区间内的重连尝试次数。 */
  attempts: number
  /** 最近一次断线区间是否以恢复收尾。 */
  recovered: boolean
}

const HTTP_STATUS_PATTERN = /^SSE (\d{3})$/

/**
 * 把一次流失败归类为脱敏类别；不读取任何正文或凭据。
 *
 * `localAbort` 必须来自**显式本地取消证据**（本端 AbortController 被中止）。
 * 缺少该证据时（例如只有浏览器网络层的 `ERR_ABORTED` 标签）绝不判定 `CLIENT_ABORT`：
 * 传输失败归类为 `NETWORK`，其余无法归类的一律 `UNKNOWN`。
 */
export function classifySseDisconnect(
  reason: unknown,
  localAbort: boolean,
): { category: AgentSseDisconnectCategory; httpStatus?: number } {
  if (localAbort) return { category: 'CLIENT_ABORT' }
  if (reason instanceof Error) {
    if (reason.name === 'AbortError') return { category: 'UNKNOWN' }
    const match = HTTP_STATUS_PATTERN.exec(reason.message)
    if (match) return { category: 'HTTP_ERROR', httpStatus: Number(match[1]) }
    if (reason instanceof TypeError) return { category: 'NETWORK' }
    return { category: 'UNKNOWN' }
  }
  return { category: 'UNKNOWN' }
}

/**
 * 按"断线区间"收敛记录：一次连续断线只发一条 `disconnect`（避免后端不可用时刷屏）；
 * 传输重新建立时发一条 `transport_reconnected`，事件/运行状态对齐后发一条 `recovered` 并结束区间。
 * 区间结束后同一运行的新断线会被重新记录。返回的可选记录由调用方决定是否输出。
 */
export class AgentSseDiagnosticTracker {
  private category: AgentSseDisconnectCategory | null = null
  private lastSequence = 0
  private attempts = 0
  private recovered = false
  private runId = ''
  private state: AgentSseConnectionState = 'UNKNOWN'

  /** 是否处于尚未恢复的断线区间（正在重连或传输已重连但未对齐）。 */
  private get episodeActive(): boolean {
    return this.state === 'RECONNECTING' || this.state === 'TRANSPORT_RECONNECTED'
  }

  /**
   * 传输已建立（HTTP 2xx 且拿到正文）。
   * 断线区间内重新建立时只标记"传输已重连"并产出一条 `transport_reconnected` 记录——
   * 仅拿到 HTTP 200 **不等于**完整事件恢复；`RECOVERED` 必须由 {@link aligned} 在事件/运行状态
   * 对齐后才成立。无断线区间时为普通连接，不产生记录。
   */
  opened(runId: string, lastSequence: number): AgentSseDiagnostic | null {
    this.runId = runId
    this.lastSequence = lastSequence
    if (!this.episodeActive) {
      if (this.state === 'UNKNOWN') this.state = 'CONNECTED'
      return null
    }
    this.state = 'TRANSPORT_RECONNECTED'
    return {
      scope: 'agent.sse',
      phase: 'transport_reconnected',
      category: this.category ?? 'UNKNOWN',
      runId,
      lastSequence,
      attempt: this.attempts,
    }
  }

  /**
   * 持久事件续接成功或运行状态对齐：断线区间真正恢复，产出一条 `recovered` 记录并结束区间。
   * 保留断线类别供"已恢复"展示；区间结束后同一运行的新断线会重新记录，不再被抑制。
   */
  aligned(): AgentSseDiagnostic | null {
    if (this.state !== 'TRANSPORT_RECONNECTED') return null
    const record: AgentSseDiagnostic = {
      scope: 'agent.sse',
      phase: 'recovered',
      category: this.category ?? 'UNKNOWN',
      runId: this.runId,
      lastSequence: this.lastSequence,
      attempt: this.attempts,
    }
    this.state = 'RECOVERED'
    this.recovered = true
    this.attempts = 0
    return record
  }

  /** 流失败：本断线区间内只在首次失败时产出一条 `disconnect`。 */
  disconnected(runId: string, reason: unknown, localAbort: boolean, lastSequence: number): AgentSseDiagnostic | null {
    const classified = classifySseDisconnect(reason, localAbort)
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
    this.attempts += 1
    if (this.episodeActive) {
      // 传输重连后尚未对齐便再次断开：区间继续，但传输已不再连接。
      this.state = 'RECONNECTING'
      return null
    }
    this.category = category
    this.state = 'RECONNECTING'
    this.recovered = false
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
    this.state = 'UNKNOWN'
  }

  snapshot(): AgentSseDiagnosticSnapshot {
    return {
      state: this.state,
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
