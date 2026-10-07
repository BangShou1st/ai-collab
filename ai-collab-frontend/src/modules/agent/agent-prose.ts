/**
 * 展示层边界：Agent 正文里的内部协议控制标记（如 `[QUESTIONS]`）不是给用户看的文字。
 * 只在展示时处理，不修改模型原始响应、持久化、后端澄清判定或用户输入续跑机制。
 *
 * 范围约束（R10）：
 * - 普通正文、代码、JSON 转义序列和 Windows 路径（如 `C:\new\notes.txt`）保持原样，
 *   不做全局字面 `\n` 反转义；
 * - 只在明确识别的协议区域内（控制标记行及其后的连续追问选项）把模型当换行写出的
 *   字面 `\n` 还原为真实换行，使选项各自成行；
 * - 追问区域与正文步骤分开返回（R11），由调用方独立渲染，不并入正文的编号列表。
 */

const QUESTION_MARKER = '[QUESTIONS]'

/** 匹配一行内的控制标记（允许字面 \n 转义或真实换行跟在其后）。 */
const MARKER_LINE = /(^|\n)\s*\[QUESTIONS\][ \t]*(?:\\n|\r?\n)?/g

/** 协议区域内单行的字面 `\n`（模型的选项分隔写法）。 */
const LITERAL_NEWLINE = /\\n/g

interface AnswerProse {
  /** 无协议区域时与输入相同；有协议区域时是剥离标记后的正文部分。 */
  text: string
  /** 识别出的追问区域（自然语言问题 + 各选项行），已还原真实换行；无追问时为 null。 */
  questions: string | null
}

/**
 * 解析回答中的协议区域：返回可展示正文与独立的追问区。
 *
 * <p>处理只针对已识别的 `[QUESTIONS]` 标记行之后的连续内容；
 * 该区域之外的正文一律原样保留（不做反转义、不重排行）。
 * 分片到达的临时帧可能先给出 `[QUE` 这样的短前缀：尚未判明时整段暂缓展示，
 * 避免先闪出半截标记再消失。</p>
 */
export function parseAgentProse(text: string): AnswerProse {
  if (!text) return { text, questions: null }
  if (text.length < QUESTION_MARKER.length && QUESTION_MARKER.startsWith(text)) {
    return { text: '', questions: null }
  }
  const markerIndex = text.indexOf(QUESTION_MARKER)
  if (markerIndex < 0) return { text, questions: null }
  const before = text.slice(0, markerIndex).replace(/\n$/, '')
  // 追问区域：标记行之后的全部内容。该区域是模型为澄清协议写出的紧凑文本，
  // 字面 \n 在这里还原为真实换行；区域之外的正文字节不动。
  const rawQuestions = text.slice(markerIndex + QUESTION_MARKER.length)
    .replace(/^(?:\\n|\r?\n)/, '')
  return { text: before, questions: rawQuestions.replace(LITERAL_NEWLINE, '\n') }
}

/** 兼容入口：只要可展示正文（含中间标记剥离），追问区域并入返回文本但保持独立空行。 */
export function visibleAgentProse(text: string): string {
  const parsed = parseAgentProse(text)
  return parsed.questions == null
    ? parsed.text
    : parsed.text + (parsed.text ? '\n\n' : '') + parsed.questions
}

/** 追问区域是否存在于回答中（供视图层独立渲染追问块）。 */
export function hasAgentQuestions(text: string): boolean {
  return parseAgentProse(text).questions != null
}

/** 仅追问区域的行（已剥离空行），供视图层渲染独立的追问列表。 */
export function agentQuestionLines(text: string): string[] {
  const parsed = parseAgentProse(text)
  if (parsed.questions == null) return []
  return parsed.questions.split('\n').map((line) => line.trim()).filter(Boolean)
}
