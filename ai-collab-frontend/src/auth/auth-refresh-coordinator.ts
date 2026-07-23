import type { AccessTokenResult, ApiResult } from '../api/types'

let refreshPromise: Promise<ApiResult<AccessTokenResult>> | null = null

export function coordinateRefresh(
  refresh: () => Promise<ApiResult<AccessTokenResult>>,
): Promise<ApiResult<AccessTokenResult>> {
  if (!refreshPromise) {
    refreshPromise = refresh().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}
