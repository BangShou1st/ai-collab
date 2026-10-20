import { describe, expect, it } from 'vitest'
import { documentFailureGuide } from './document-failure'

describe('document failure guidance', () => {
  it('separates system Embedding responsibility from personal AI settings', () => {
    const guide = documentFailureGuide('文档向量化失败，请检查模型配置后重试')
    expect(guide.next).toContain('系统管理员')
    expect(guide.next).toContain('系统 Embedding')
    expect(guide.next).toContain('具体原因尚未确认')
    expect(documentFailureGuide('文档索引暂不可用：系统尚未配置嵌入模型，请联系管理员在管理中心配置后重试').next).toContain('个人 AI 设置不用于文档索引')
  })
  it.each([null, '', 'Bearer secret sk-test', '文档解析超时 token=private', 'toString', '文档处理失败，请重试'])('does not infer a cause or echo unknown text: %s', message => {
    const guide = documentFailureGuide(message)
    expect(guide.title).toContain('原因暂不明确')
    expect(JSON.stringify(guide)).not.toMatch(/Bearer|sk-test|private|toString/)
  })
  it.each([
    ['文件存储服务暂时不可用，请重试', '文件存储'],
    ['文档解析失败', '原文件'],
    ['文档解析超时', '稍后重试'],
    ['文档解析服务繁忙，请稍后重试', '稍后重试'],
    ['TXT 与 Markdown 必须使用 UTF-8 编码', '直接重试不会修正'],
    ['文档文本超过安全解析上限', '拆分文件'],
  ])('offers actionable guidance for known safe message %s', (message, action) => {
    expect(documentFailureGuide(message).next).toContain(action)
    expect(documentFailureGuide(message).next).toContain('项目所有者或管理员')
  })
})
