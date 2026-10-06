/**
 * 展示层边界：Agent 正文里的内部协议控制前缀（如 `[QUESTIONS]`）不是给用户看的文字。
 * 只在展示时剥离，不修改模型原始响应、持久化、后端澄清判定或用户输入续跑机制。
 */
const QUESTION_MARKER = '[QUESTIONS]'

/**
 * 去掉开头的澄清控制标记，只保留自然语言问题；其他正文原样返回。
 *
 * <p>分片到达的临时帧可能先给出 `[QUE` 这样的短前缀：尚未判明是否为控制标记前暂缓展示，
 * 避免先闪出半截标记再消失。</p>
 */
export function visibleAgentProse(text: string): string {
  if (!text) return text
  if (text.length < QUESTION_MARKER.length && QUESTION_MARKER.startsWith(text)) return ''
  if (text.startsWith(QUESTION_MARKER)) return text.slice(QUESTION_MARKER.length).replace(/^\s+/, '')
  return text
}
