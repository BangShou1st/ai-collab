import { afterEach, describe, expect, it, vi } from 'vitest'
import { confirmationKey, clearConfirmationKey } from './planning-api'

// Mock sessionStorage and crypto for vitest node environment
const storage = new Map<string, string>()
vi.stubGlobal('sessionStorage', {
  getItem: (key: string) => storage.get(key) ?? null,
  setItem: (key: string, value: string) => { storage.set(key, value) },
  removeItem: (key: string) => { storage.delete(key) },
  clear: () => storage.clear(),
})
let uuidCounter = 0
vi.stubGlobal('crypto', {
  randomUUID: () => `00000000-0000-0000-0000-${String(++uuidCounter).padStart(12, '0')}`,
})

afterEach(() => {
  storage.clear()
  uuidCounter = 0
})

describe('confirmationKey', () => {
  it('generates a UUID and stores in sessionStorage', () => {
    const key = confirmationKey('proj-1', 'plan-1', 'ver-1')
    expect(key).toBe('00000000-0000-0000-0000-000000000001')
    expect(storage.get('planning-confirm:proj-1:plan-1:ver-1')).toBe(key)
  })

  it('returns existing key on second call (idempotent)', () => {
    const first = confirmationKey('proj-1', 'plan-1', 'ver-1')
    const second = confirmationKey('proj-1', 'plan-1', 'ver-1')
    expect(first).toBe(second)
  })

  it('isolates keys by project+plan+version', () => {
    const a = confirmationKey('proj-1', 'plan-1', 'ver-1')
    const b = confirmationKey('proj-1', 'plan-1', 'ver-2')
    const c = confirmationKey('proj-2', 'plan-1', 'ver-1')
    expect(a).not.toBe(b)
    expect(a).not.toBe(c)
    expect(b).not.toBe(c)
  })
})

describe('clearConfirmationKey', () => {
  it('removes the stored key', () => {
    confirmationKey('proj-1', 'plan-1', 'ver-1')
    expect(storage.has('planning-confirm:proj-1:plan-1:ver-1')).toBe(true)
    clearConfirmationKey('proj-1', 'plan-1', 'ver-1')
    expect(storage.has('planning-confirm:proj-1:plan-1:ver-1')).toBe(false)
  })

  it('does not affect other keys', () => {
    confirmationKey('proj-1', 'plan-1', 'ver-1')
    confirmationKey('proj-1', 'plan-1', 'ver-2')
    clearConfirmationKey('proj-1', 'plan-1', 'ver-1')
    expect(storage.has('planning-confirm:proj-1:plan-1:ver-2')).toBe(true)
  })
})
