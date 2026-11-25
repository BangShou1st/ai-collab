import type { DiscoveredModelMetadata } from './user-ai-api'

/**
 * 现代主流大模型在 `/models` 未上报 `max_output_tokens` 时的通用兜底上限。
 * DeepSeek / Gemini / Qwen / Claude 当前常规默认普遍在 4096~8192，
 * 取 8192 可避免长工具总结或结构化生成被过早截断。
 */
export const DEFAULT_MAX_OUTPUT_TOKENS = 8192

export function clampMaxOutputTokens(value: number): number {
  return Math.min(131072, Math.max(1024, Math.round(value)))
}

export function resolveAutoMaxOutputTokens(
  meta?: Pick<DiscoveredModelMetadata, 'maxOutputTokens'> | null,
): number {
  if (meta?.maxOutputTokens && meta.maxOutputTokens > 0) {
    return clampMaxOutputTokens(meta.maxOutputTokens)
  }
  return DEFAULT_MAX_OUTPUT_TOKENS
}

export function shouldAutoFillMaxOutputTokens(options: {
  mode: 'create' | 'edit'
  manuallyEdited: boolean
  currentValue: number
  modelChanged: boolean
  meta?: Pick<DiscoveredModelMetadata, 'maxOutputTokens'> | null
}): boolean {
  if (options.manuallyEdited) return false
  if (options.meta?.maxOutputTokens && options.meta.maxOutputTokens > 0) return true
  return options.mode === 'create' || options.modelChanged || options.currentValue < 2048
}

export function formatDiscoveredModelOption(model: DiscoveredModelMetadata): string {
  if (!model.contextWindowTokens && !model.maxOutputTokens) return model.id
  const parts: string[] = []
  if (model.contextWindowTokens) parts.push(`窗口 ${model.contextWindowTokens.toLocaleString('zh-CN')}`)
  if (model.maxOutputTokens) parts.push(`输出 ${model.maxOutputTokens.toLocaleString('zh-CN')}`)
  return `${model.id} (${parts.join(' · ')})`
}
