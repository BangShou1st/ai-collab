export interface KnowledgeEvalFormRow {
  id: string
  question: string
  expectedDocumentIds: string[]
}

export interface KnowledgeEvalRequestCase {
  question: string
  expectedDocumentIds: string[]
}

export function validateEvalRows(
  rows: KnowledgeEvalFormRow[],
): { valid: boolean; message: string } {
  if (!rows.length) return { valid: false, message: '请至少添加一个评测问题' }
  for (const [index, row] of rows.entries()) {
    if (!row.question.trim()) {
      return { valid: false, message: `第 ${index + 1} 个问题不能为空` }
    }
    if (!row.expectedDocumentIds.length) {
      return {
        valid: false,
        message: `请为第 ${index + 1} 个问题选择至少一个预期文档`,
      }
    }
  }
  return { valid: true, message: '' }
}

export function toEvalRequest(rows: KnowledgeEvalFormRow[]): KnowledgeEvalRequestCase[] {
  return rows.map(row => ({
    question: row.question.trim(),
    expectedDocumentIds: [...new Set(row.expectedDocumentIds)],
  }))
}
