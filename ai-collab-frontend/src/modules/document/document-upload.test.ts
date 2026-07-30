import { describe, expect, it, vi } from 'vitest'
import {
  createUploadCandidates,
  uploadCandidatesSequentially,
  validateUploadCandidate,
} from './document-upload'

describe('document multi upload', () => {
  it('creates one editable candidate per selected file', () => {
    const candidates = createUploadCandidates([
      new File(['a'], '需求说明.pdf', { type: 'application/pdf' }),
      new File(['b'], '接口设计.md', { type: 'text/markdown' }),
    ])

    expect(candidates.map(item => item.displayName)).toEqual(['需求说明', '接口设计'])
  })

  it('continues uploading remaining files when one file fails', async () => {
    const candidates = createUploadCandidates([
      new File(['a'], 'a.txt'),
      new File(['b'], 'b.txt'),
      new File(['c'], 'c.txt'),
    ])
    const upload = vi.fn()
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('storage unavailable'))
      .mockResolvedValueOnce(undefined)

    const results = await uploadCandidatesSequentially(candidates, upload)

    expect(upload).toHaveBeenCalledTimes(3)
    expect(results.map(item => item.status)).toEqual(['success', 'failed', 'success'])
  })

  it('returns a concrete validation reason for each invalid file', () => {
    const [candidate] = createUploadCandidates([new File([], 'empty.txt')])

    expect(validateUploadCandidate(candidate!)).toBe('empty.txt：不能上传空文件')
  })
})
