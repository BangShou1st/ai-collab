import { describe, expect, it } from 'vitest'
import { toEvalRequest, validateEvalRows } from './knowledge-eval-form'

describe('knowledge evaluation form', () => {
  it('normalizes valid questions and expected documents', () => {
    const rows = [{
      id: 'row-1',
      question: '  项目验收需要哪些材料？  ',
      expectedDocumentIds: ['doc-1', 'doc-2'],
    }]

    expect(validateEvalRows(rows)).toEqual({ valid: true, message: '' })
    expect(toEvalRequest(rows)).toEqual([{
      question: '项目验收需要哪些材料？',
      expectedDocumentIds: ['doc-1', 'doc-2'],
    }])
  })

  it('rejects an empty question', () => {
    expect(validateEvalRows([{
      id: 'row-1',
      question: ' ',
      expectedDocumentIds: ['doc-1'],
    }])).toEqual({ valid: false, message: '第 1 个问题不能为空' })
  })

  it('requires at least one expected document for every question', () => {
    expect(validateEvalRows([{
      id: 'row-1',
      question: '项目目标是什么？',
      expectedDocumentIds: [],
    }])).toEqual({ valid: false, message: '请为第 1 个问题选择至少一个预期文档' })
  })
})
