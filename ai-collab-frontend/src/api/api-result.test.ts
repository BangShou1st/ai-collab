import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiContractError, normalizeApiError, showApiError } from './api-result'

const messageError = vi.hoisted(() => vi.fn())

vi.mock('element-plus', () => ({
  ElMessage: { error: messageError },
}))

beforeEach(() => messageError.mockClear())

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

  it('shows one grouped contextual error while preserving a concrete backend reason', () => {
    const error = new AxiosError(
      'not found', 'ERR_BAD_REQUEST', undefined, undefined,
      {
        status: 404, statusText: 'Not Found', headers: {}, config: { headers: {} } as never,
        data: {
          code: 'PROJECT_OWNERSHIP_TARGET_NOT_MEMBER',
          message: '目标成员已不在项目中',
          data: null,
        },
      },
    )

    const message = showApiError(error, '项目所有权转移')

    expect(message).toBe('项目所有权转移失败：目标成员已不在项目中')
    expect(messageError).toHaveBeenCalledOnce()
    expect(messageError).toHaveBeenCalledWith({
      message: '项目所有权转移失败：目标成员已不在项目中',
      grouping: true,
    })
  })

  it('uses a specific safe contract error instead of reducing it to an unknown error', () => {
    const message = showApiError(
      new ApiContractError('MCP 连接响应契约错误：toolAllowlist 必须是字符串数组'),
      'MCP 连接加载',
    )

    expect(message).toBe('MCP 连接加载失败：MCP 连接响应契约错误：toolAllowlist 必须是字符串数组')
    expect(messageError).toHaveBeenCalledOnce()
  })
})
