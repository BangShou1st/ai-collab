import DOMPurify from 'dompurify'
import { marked } from 'marked'

/** 渲染 Markdown 并做严格净化：禁止外链、图片、脚本与内联样式。 */
export function markdown(content: string): string {
  const rendered = marked.parse(content, { async: false, breaks: true }) as string
  return DOMPurify.sanitize(rendered, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['iframe', 'object', 'embed', 'form', 'img', 'a'],
    FORBID_ATTR: ['style'],
  })
}

export function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

export function similarity(value: number): string {
  return `${Math.max(0, Math.min(1, value)) * 100}`.replace(/(\.\d).*$/, '$1') + '%'
}
