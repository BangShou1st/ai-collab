import { describe, expect, it } from 'vitest'
import {
  duplicateVersionJunction,
  endpointFixSuggestions,
  inferenceUrlPreview,
  previewEndpoint,
} from './endpoint-rules'

describe('endpoint-rules（Base URL / API Path 组合规则，T04）', () => {
  it('detects the duplicated /v1 junction from the P7 acceptance reproduction', () => {
    expect(duplicateVersionJunction('https://api-inference.modelscope.cn/v1', '/v1/chat/completions')).toBe('v1')
    expect(previewEndpoint('https://api-inference.modelscope.cn/v1', '/v1/chat/completions').inferenceUrl)
      .toBe('https://api-inference.modelscope.cn/v1/v1/chat/completions')
  })

  it('keeps legitimate protocol paths untouched', () => {
    // Gemini 官方 /v1beta 与 Anthropic /v1：Base URL 不含版本段，不构成拼接重复
    expect(duplicateVersionJunction('https://generativelanguage.googleapis.com', '/v1beta/models/{model}:generateContent')).toBeNull()
    expect(duplicateVersionJunction('https://api.anthropic.com', '/v1/messages')).toBeNull()
    expect(duplicateVersionJunction('https://api.openai.com', '/v1/chat/completions')).toBeNull()
    // 特殊自定义网关：非版本段重复不误伤
    expect(duplicateVersionJunction('https://gw.example.com/inference', '/inference/chat/completions')).toBeNull()
    // 版本不同不构成重复
    expect(duplicateVersionJunction('https://gw.example.com/v1', '/v2/chat/completions')).toBeNull()
  })

  it('offers one-click fixes on both sides without blanket path rewriting', () => {
    const fix = endpointFixSuggestions('https://api-inference.modelscope.cn/v1', '/v1/chat/completions')
    expect(fix.fixedBaseUrl).toBe('https://api-inference.modelscope.cn/')
    expect(fix.fixedApiPath).toBe('/chat/completions')
    // 无重复时不给修正建议
    expect(endpointFixSuggestions('https://api.openai.com', '/v1/chat/completions')).toEqual({
      fixedBaseUrl: null,
      fixedApiPath: null,
    })
  })

  it('derives the discovery URL from the inference path prefix', () => {
    expect(previewEndpoint('https://api.example.com', '/v1/chat/completions').discoveryUrl)
      .toBe('https://api.example.com/v1/models')
    // Gemini：路径自带 models 段，截断到该段
    expect(previewEndpoint('https://generativelanguage.googleapis.com', '/v1beta/models/{model}:generateContent').discoveryUrl)
      .toBe('https://generativelanguage.googleapis.com/v1beta/models')
    // Anthropic /v1/messages
    expect(previewEndpoint('https://api.anthropic.com', '/v1/messages').discoveryUrl)
      .toBe('https://api.anthropic.com/v1/models')
  })
})
