const FOLDER_HOST_SUFFIXES = ['.feishu.cn', '.larksuite.com']
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{6,128}$/

export type FolderInputResult =
  | { ok: true; token: string; source: 'url' | 'token' }
  | { ok: false; reason: 'empty' | 'not-folder-link' | 'unsupported-host' | 'bad-token' }

/**
 * 只把可明确确认的飞书 / Lark 文件夹路径（/drive/folder/{token}）识别为文件夹链接；
 * Wiki、文档、表格等一律拒绝，避免把任意链接最后一段误当 folderToken。
 * 非 URL 输入按原始 folderToken 兼容处理。
 */
export function parseFolderInput(input: string): FolderInputResult {
  const value = input.trim()
  if (!value) return { ok: false, reason: 'empty' }
  if (!/^https?:\/\//i.test(value)) {
    return TOKEN_PATTERN.test(value)
      ? { ok: true, token: value, source: 'token' }
      : { ok: false, reason: 'bad-token' }
  }
  let url: URL
  try {
    url = new URL(value)
  } catch {
    return { ok: false, reason: 'bad-token' }
  }
  const host = url.hostname.toLowerCase()
  const trusted = FOLDER_HOST_SUFFIXES.some(suffix => host.endsWith(suffix))
  if (!trusted) return { ok: false, reason: 'unsupported-host' }
  const segments = url.pathname.split('/').filter(Boolean)
  if (segments.length !== 3 || segments[0] !== 'drive' || segments[1] !== 'folder') {
    return { ok: false, reason: 'not-folder-link' }
  }
  const token = segments[2]
  if (!TOKEN_PATTERN.test(token)) return { ok: false, reason: 'not-folder-link' }
  return { ok: true, token, source: 'url' }
}

export const folderInputErrorText: Record<Exclude<FolderInputResult, { ok: true }>['reason'], string> = {
  empty: '',
  'not-folder-link': '这不是飞书文件夹链接。请粘贴形如 https://xxx.feishu.cn/drive/folder/xxx 的文件夹链接，或输入原始 folderToken；Wiki、文档、表格链接不受支持。',
  'unsupported-host': '链接格式不受支持，仅识别 feishu.cn 与 larksuite.com 域名下的文件夹链接。',
  'bad-token': '输入既不是文件夹链接，也不是有效的 folderToken（仅支持字母、数字、下划线和短横线）。',
}
