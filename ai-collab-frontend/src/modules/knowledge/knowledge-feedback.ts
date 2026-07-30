import type { KnowledgeMessage } from './types'

export function isFeedbackEligible(message: KnowledgeMessage): boolean {
  return message.role === 'ASSISTANT' && Boolean(message.id)
}
