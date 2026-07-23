import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { router } from '../src/router'
import { useAuthStore } from '../src/stores/auth-store'

describe('route guard', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    const store = useAuthStore()
    store.initialized = true
    await router.push('/login')
    await router.isReady()
  })

  it('redirects an unauthenticated user away from auth-test', async () => {
    await router.push('/auth-test')
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
