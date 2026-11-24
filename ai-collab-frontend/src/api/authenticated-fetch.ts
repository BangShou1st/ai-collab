import { getActivePinia } from 'pinia'
import { handleUnauthorized } from '../auth/unauthorized-handler'
import { useAuthStore } from '../stores/auth-store'
import { syncMaintenanceFromResponsePayload } from '../stores/system-status-store'

export async function authenticatedFetch(
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> {
  return execute(input, init, false)
}

async function execute(
  input: RequestInfo | URL,
  init: RequestInit,
  authRetried: boolean,
): Promise<Response> {
  const auth = useAuthStore()
  const headers = {
    ...(init.headers as Record<string, string> | undefined),
    ...(auth.accessToken ? { Authorization: `Bearer ${auth.accessToken}` } : {}),
  }
  const response = await fetch(input, { ...init, headers })
  if (response.status === 503 && getActivePinia()) {
    try {
      const cloned = response.clone()
      const payload: unknown = await cloned.json()
      syncMaintenanceFromResponsePayload(response.status, payload)
    } catch {
      // 非 JSON 503 响应忽略
    }
    return response
  }
  if (response.status !== 401 || authRetried) {
    return response
  }

  try {
    await auth.refresh()
    return execute(input, init, true)
  } catch {
    auth.clearAuth()
    handleUnauthorized()
    return response
  }
}
