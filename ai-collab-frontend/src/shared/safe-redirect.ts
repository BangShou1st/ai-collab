import type { Router } from 'vue-router'

const FALLBACK_PATH = '/home'

export function resolveSafeRedirect(
  router: Router,
  candidate: unknown,
  fallback = FALLBACK_PATH,
): string {
  if (
    typeof candidate !== 'string'
    || !candidate.startsWith('/')
    || candidate.startsWith('//')
    || candidate.includes('\\')
    || /[\r\n]/.test(candidate)
    || /^[a-z][a-z\d+.-]*:/i.test(candidate)
  ) {
    return fallback
  }

  try {
    const resolved = router.resolve(candidate)
    return resolved.matched.length ? resolved.fullPath : fallback
  } catch {
    return fallback
  }
}
