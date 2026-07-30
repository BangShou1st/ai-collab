export type UploadStatus = 'pending' | 'uploading' | 'success' | 'failed'

export interface UploadCandidate {
  id: string
  file: File
  displayName: string
  status: UploadStatus
  error: unknown | null
}

const MAX_FILE_SIZE = 20 * 1024 * 1024
const SUPPORTED_EXTENSION = /\.(pdf|docx|md|markdown|txt)$/i

export function createUploadCandidates(files: Iterable<File>): UploadCandidate[] {
  return Array.from(files, file => ({
    id: crypto.randomUUID(),
    file,
    displayName: file.name.replace(/\.[^.]+$/, ''),
    status: 'pending',
    error: null,
  }))
}

export function validateUploadCandidate(candidate: UploadCandidate): string {
  const { file, displayName } = candidate
  if (file.size <= 0) return `${file.name}：不能上传空文件`
  if (file.size > MAX_FILE_SIZE) return `${file.name}：文件大小不能超过 20MB`
  if (file.name.length > 180) return `${file.name}：文件名不能超过 180 个字符`
  if (!displayName.trim()) return `${file.name}：显示名称不能为空`
  if (displayName.length > 180) return `${file.name}：显示名称不能超过 180 个字符`
  if (!SUPPORTED_EXTENSION.test(file.name)) {
    return `${file.name}：仅支持 PDF、DOCX、Markdown 和 TXT 文件`
  }
  return ''
}

export async function uploadCandidatesSequentially(
  candidates: UploadCandidate[],
  upload: (candidate: UploadCandidate) => Promise<unknown>,
): Promise<UploadCandidate[]> {
  for (const candidate of candidates) {
    candidate.status = 'uploading'
    candidate.error = null
    try {
      await upload(candidate)
      candidate.status = 'success'
    } catch (error) {
      candidate.status = 'failed'
      candidate.error = error
    }
  }
  return candidates
}
