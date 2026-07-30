import { AxiosError } from 'axios'
import { describe, expect, it } from 'vitest'
import { normalizeApiError } from './api-result'

describe('normalizeApiError', () => {
  it('keeps a concrete safe backend validation message', () => {
    const error = new AxiosError(
      'bad request',
      'ERR_BAD_REQUEST',
      undefined,
      undefined,
      {
        status: 400,
        statusText: 'Bad Request',
        headers: {},
        config: { headers: {} } as never,
        data: {
          code: 'VALIDATION_ERROR',
          message: '截止日期不能早于开始日期',
          data: null,
        },
      },
    )

    expect(normalizeApiError(error).message).toBe('截止日期不能早于开始日期')
  })
})
