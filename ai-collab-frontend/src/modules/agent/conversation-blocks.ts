import type { AgentActivity } from './agent-activity'
import type { AgentMessage } from './types'

export interface ConversationBlockMessage { kind: 'message'; message: AgentMessage }
export interface ConversationBlockActivities { kind: 'activities'; items: AgentActivity[] }
export type ConversationBlock = ConversationBlockMessage | ConversationBlockActivities

/**
 * Build run-chronological conversation blocks for the current/latest run:
 * USER goal -> activities -> ASSISTANT result. Older runs render as plain messages.
 * Only the already-loaded latest run history is used; no extra per-run fetching.
 */
export function buildConversationBlocks(
  messages: AgentMessage[],
  runId: string | null,
  acts: AgentActivity[],
): ConversationBlock[] {
  const blocks: ConversationBlock[] = []
  if (!runId || !acts.length) {
    for (const m of messages) blocks.push({ kind: 'message', message: m })
    return blocks
  }
  const matchIndex = messages.findIndex((m) => m.role === 'USER' && (m.runId ?? null) === runId)
  if (matchIndex >= 0) {
    for (let i = 0; i <= matchIndex; i++) blocks.push({ kind: 'message', message: messages[i] })
    blocks.push({ kind: 'activities', items: acts })
    for (let i = matchIndex + 1; i < messages.length; i++) blocks.push({ kind: 'message', message: messages[i] })
    return blocks
  }
  let lastUserIndex = -1
  messages.forEach((m, i) => { if (m.role === 'USER') lastUserIndex = i })
  messages.forEach((m, i) => {
    blocks.push({ kind: 'message', message: m })
    if (i === lastUserIndex) blocks.push({ kind: 'activities', items: acts })
  })
  if (lastUserIndex < 0) blocks.push({ kind: 'activities', items: acts })
  return blocks
}
