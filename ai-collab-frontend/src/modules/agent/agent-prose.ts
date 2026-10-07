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
 *
 * 协议区域识别（F7 修复）：
 * - `[QUESTIONS]` 只有在**独占一行**（行首仅允许空白）时才是控制标记；
 * - 出现在代码围栏（``` / ~~~）、行内代码（`...`）或普通引述里的同名文字
 *   是**正文内容**，不触发分段，也不做反转义；
 * - 误判的代价很高：一旦误判，其后全部内容都会被反转义，Windows 路径
 *   （`C:\new\notes.txt`）会被改成真实换行、代码围栏会被拆坏。
 */

const QUESTION_MARKER = '[QUESTIONS]'

/**
 * 匹配一行内的控制标记：标记必须是该行的首个非空白内容，
 * 其后允许直接跟字面 `\n` 转义或真实换行（模型的紧凑写法）。
 */
const CONTROL_LINE = /^[ \t]*\[QUESTIONS\][ \t]*(?:\\n|\r?\n)?/

/** 协议区域内单行的字面 `\n`（模型的选项分隔写法）。 */
const LITERAL_NEWLINE = /\\n/g

interface AnswerProse {
  /** 无协议区域时与输入相同；有协议区域时是剥离标记后的正文部分。 */
  text: string
  /** 识别出的追问区域（自然语言问题 + 各选项行），已还原真实换行；无追问时为 null。 */
  questions: string | null
}

/**
 * 判断某个位置是否落在代码围栏或行内代码区域内。
 *
 * <p>只做确定性扫描，不引入 Markdown 解析依赖：逐行跟踪围栏开闭，
 * 并在非围栏行内按反引号配对跳过行内代码。</p>
 */
function insideCode(text: string, position: number): boolean {
  let fence: string | null = null
  let lineStart = 0
  const lines = text.split('\n')
  for (const line of lines) {
    const lineEnd = lineStart + line.length
    const trimmed = line.trimStart()
    const fenceMatch = /^(```+|~~~+)/.exec(trimmed)
    // 判断目标位置是否落在当前行
    if (position >= lineStart && position <= lineEnd) {
      if (fence !== null) return true
      return insideInlineCode(line, position - lineStart)
    }
    // 围栏开/闭：只有行首出现的围栏才切换状态（与 Markdown 一致）
    if (fenceMatch) {
      const marker = fenceMatch[1][0].repeat(3)
      if (fence === null) fence = marker
      else if (marker === fence) fence = null
    }
    lineStart = lineEnd + 1
  }
  return fence !== null
}

/** 行内代码（`...`）配对判定：位置是否落在成对的反引号之间。 */
function insideInlineCode(line: string, offset: number): boolean {
  let open = -1
  for (let index = 0; index < line.length; index++) {
    if (line[index] !== '`') continue
    if (open < 0) {
      open = index
    } else {
      if (offset > open && offset < index) return true
      open = -1
    }
  }
  return false
}

/**
 * 找到真正的协议控制标记位置；没有时返回 -1。
 *
 * <p>只接受"独占一行"的标记：标记前的最后一个换行到标记之间只能有空白。
 * 这样正文里解释该标记、行内代码示例、引用块内容都不会被误判。
 * 代码围栏与行内代码区域内的标记同样跳过。</p>
 */
function findControlMarker(text: string): number {
  let searchFrom = 0
  while (searchFrom <= text.length - QUESTION_MARKER.length) {
    const found = text.indexOf(QUESTION_MARKER, searchFrom)
    if (found < 0) return -1
    // 行首校验：标记前只能是行首或换行后的空白
    const lineStart = text.lastIndexOf('\n', found - 1) + 1
    const prefix = text.slice(lineStart, found)
    const atLineStart = /^[ \t]*$/.test(prefix)
    if (atLineStart && !insideCode(text, found)) return found
    searchFrom = found + 1
  }
  return -1
}

/**
 * 解析回答中的协议区域：返回可展示正文与独立的追问区。
 *
 * <p>处理只针对已识别的 `[QUESTIONS]` 控制行之后的连续内容；
 * 该区域之外的正文一律原样保留（不做反转义、不重排行）。
 * 分片到达的临时帧可能先给出 `[QUE` 这样的短前缀：尚未判明时整段暂缓展示，
 * 避免先闪出半截标记再消失。</p>
 */
export function parseAgentProse(text: string): AnswerProse {
  if (!text) return { text, questions: null }
  if (text.length < QUESTION_MARKER.length && QUESTION_MARKER.startsWith(text)) {
    return { text: '', questions: null }
  }
  const markerIndex = findControlMarker(text)
  if (markerIndex < 0) return { text, questions: null }
  // 标记前的内容是正文：剥掉尾部的空白行，避免分段处留下多余空行
  const before = text.slice(0, markerIndex).replace(/\s+$/, '')
  // 追问区域：控制行之后的全部内容。该区域是模型为澄清协议写出的紧凑文本，
  // 字面 \n 在这里还原为真实换行；区域之外的正文字节不动。
  const controlLine = CONTROL_LINE.exec(text.slice(markerIndex))
  const consumed = controlLine ? controlLine[0].length : QUESTION_MARKER.length
  const rawQuestions = text.slice(markerIndex + consumed)
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
