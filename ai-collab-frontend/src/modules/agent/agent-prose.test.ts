import { describe, expect, it } from 'vitest'
import { parseAgentProse, visibleAgentProse, hasAgentQuestions, agentQuestionLines } from './agent-prose'

describe('visibleAgentProse', () => {
  it('strips leading [QUESTIONS] marker and keeps the question', () => {
    expect(visibleAgentProse('[QUESTIONS]\n请确认本周的统计范围。')).toBe('请确认本周的统计范围。')
  })

  it('withholds a partial marker prefix until it is identified', () => {
    expect(visibleAgentProse('[QUE')).toBe('')
    expect(visibleAgentProse('[QUESTIONS]')).toBe('')
  })

  it('strips a mid-text marker line the model embedded in a long answer', () => {
    const text = '前面的结论。\n\n[QUESTIONS]\\n请问验收标准目前记录在哪里？\\n1. 项目文档\\n2. 任务描述'
    const visible = visibleAgentProse(text)
    expect(visible).not.toContain('[QUESTIONS]')
    expect(visible).toContain('前面的结论。')
    expect(visible).toContain('请问验收标准目前记录在哪里？')
  })

  it('converts literal \\n escapes inside the questions area so options render on separate lines', () => {
    const text = '结论成立。\n[QUESTIONS]\\n请问范围？\\n1. 文档\\n2. 任务'
    const visible = visibleAgentProse(text)
    expect(visible).not.toContain('\\n')
    expect(visible.split('\n')).toContain('1. 文档')
  })

  it('leaves ordinary text untouched', () => {
    expect(visibleAgentProse('普通回答，无控制标记。')).toBe('普通回答，无控制标记。')
  })
})

describe('协议区域之外的正文保持原样（R10 回归）', () => {
  it('keeps Windows paths intact', () => {
    const text = '请把导出文件放到 C:\\new\\notes.txt 目录。'
    expect(visibleAgentProse(text)).toBe(text)
    expect(parseAgentProse(text)).toEqual({ text, questions: null })
  })

  it('keeps literal \\n in JSON escapes and code lines intact outside protocol areas', () => {
    const text = '返回的 JSON 为 {"body":"第一行\\n第二行"}，注意转义序列。'
    expect(visibleAgentProse(text)).toBe(text)
  })

  it('keeps literal \\n in a plain list line intact (no global unescape)', () => {
    const text = '配置示例：a\\nb 表示字面量。'
    expect(visibleAgentProse(text)).toBe(text)
  })

  it('only unescapes inside the identified questions area', () => {
    const text = '正文保留 C:\\new\\notes.txt 原样。[QUESTIONS]\\n范围？\\n1. 文档'
    const parsed = parseAgentProse(text)
    expect(parsed.text).toContain('C:\\new\\notes.txt')
    expect(parsed.questions).not.toContain('\\n')
  })
})

describe('追问区域独立分段（R11 回归）', () => {
  const sample = '## 建议的后续步骤\n\n1. 配置嵌入模型。\n2. 上传文档。\n\n[QUESTIONS]\\n请问验收标准记录在哪里？\\n1. 项目文档\\n2. 任务描述\\n3. 外部系统'

  it('reports the questions area separately from the prose', () => {
    const parsed = parseAgentProse(sample)
    expect(hasAgentQuestions(sample)).toBe(true)
    expect(parsed.text).toContain('## 建议的后续步骤')
    expect(parsed.text).not.toContain('[QUESTIONS]')
    expect(parsed.questions).toContain('请问验收标准记录在哪里？')
  })

  it('returns question lines including options as separate entries', () => {
    const lines = agentQuestionLines(sample)
    expect(lines[0]).toBe('请问验收标准记录在哪里？')
    expect(lines).toContain('1. 项目文档')
    expect(lines).toContain('2. 任务描述')
    expect(lines).toContain('3. 外部系统')
    expect(lines).not.toContain('1. 配置嵌入模型。')
  })

  it('keeps a normal answer free of question blocks', () => {
    expect(hasAgentQuestions('普通回答')).toBe(false)
    expect(agentQuestionLines('普通回答')).toEqual([])
  })
})
