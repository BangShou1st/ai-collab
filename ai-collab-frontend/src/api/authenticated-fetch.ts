import { handleUnauthorized } from '../auth/unauthorized-handler'
import { useAuthStore } from '../stores/auth-store'

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
