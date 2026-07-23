import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import { normalizeApiError, sanitizeApiData } from '../src/api/api-result'

function axiosError(status: number, data: unknown): AxiosError {
  return new AxiosError(
    'request failed',
    undefined,
    undefined,
    undefined,
    {
      status,
      statusText: '',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data,
    },
  )
}

describe('safe API results', () => {
  it('prefers the backend ApiResponse message without exposing transport details', () => {
    const error = axiosError(403, {
      code: 'ORIGIN_NOT_ALLOWED',
      message: '来源校验失败',
      data: {
        password: 'never-render-this-password',
        accessToken: 'never-render-this-token',
        profile: { name: 'Owner' },
      },
    })

    const result = normalizeApiError(error)
    const rendered = JSON.stringify(result)

    expect(result).toEqual({
      httpStatus: 403,
      code: 'ORIGIN_NOT_ALLOWED',
      message: '来源校验失败',
      data: { profile: { name: 'Owner' } },
    })
    expect(rendered).not.toContain('never-render-this')
    expect(rendered).not.toMatch(/authorization|cookie|password|token/i)
  })

  it('returns a clear safe message for network failures', () => {
    const result = normalizeApiError(new AxiosError('Network Error', 'ERR_NETWORK'))

    expect(result.httpStatus).toBeNull()
    expect(result.code).toBe('NETWORK_ERROR')
    expect(result.message).toBe('网络连接失败，请检查网络后重试')
    expect(result.data).toBeNull()
  })

  it('removes sensitive fields recursively from successful response data', () => {
    const safe = sanitizeApiData({
      user: { username: 'owner', password: 'hidden' },
      accessToken: 'hidden',
      nested: { Authorization: 'hidden', Cookie: 'hidden', value: 1 },
    })

    expect(safe).toEqual({
      user: { username: 'owner' },
      nested: { value: 1 },
    })
  })
})
