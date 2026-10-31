import { describe, expect, it } from 'vitest'
import { parseFolderInput } from './feishu-folder-url'

describe('parseFolderInput', () => {
  it('extracts the token from a feishu folder URL', () => {
    const result = parseFolderInput('https://example.feishu.cn/drive/folder/fldcnABC123')
    expect(result).toEqual({ ok: true, token: 'fldcnABC123', source: 'url' })
  })

  it('extracts the token from a lark folder URL with query and trailing slash', () => {
    const result = parseFolderInput('https://tenant.larksuite.com/drive/folder/FOLDER_9x/?from=share')
    expect(result).toEqual({ ok: true, token: 'FOLDER_9x', source: 'url' })
  })

  it('keeps accepting a raw folder token', () => {
    expect(parseFolderInput('fldcnRAW123')).toEqual({ ok: true, token: 'fldcnRAW123', source: 'token' })
  })

  it('rejects wiki, document and sheet links instead of taking their last segment', () => {
    expect(parseFolderInput('https://example.feishu.cn/wiki/Xy9abcDEF')?.ok).toBe(false)
    expect(parseFolderInput('https://example.feishu.cn/docx/doxcnABC123')?.ok).toBe(false)
    expect(parseFolderInput('https://example.feishu.cn/sheets/shtcnABC123')?.ok).toBe(false)
  })

  it('rejects untrusted hosts and arbitrary URLs', () => {
    expect(parseFolderInput('https://evil.example.com/drive/folder/fldcnABC123')?.ok).toBe(false)
    expect(parseFolderInput('https://feishu.cn.evil.io/drive/folder/fldcnABC123')?.ok).toBe(false)
  })

  it('rejects folder paths with a malformed token', () => {
    expect(parseFolderInput('https://example.feishu.cn/drive/folder/not%20valid')?.ok).toBe(false)
    expect(parseFolderInput('https://example.feishu.cn/drive/folder')?.ok).toBe(false)
  })

  it('rejects garbage input that is neither URL nor token', () => {
    expect(parseFolderInput('../../etc/passwd')?.ok).toBe(false)
    expect(parseFolderInput('')?.ok).toBe(false)
  })
})
