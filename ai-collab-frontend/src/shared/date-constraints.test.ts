import { describe, expect, it } from 'vitest'
import {
  isEndDateDisabled,
  isStartDateDisabled,
  validateDateRange,
} from './date-constraints'

describe('shared date constraints', () => {
  it('disables start dates after the selected end date or outside the project', () => {
    expect(isStartDateDisabled(new Date(2026, 7, 5), '2026-08-01', '2026-08-31', '2026-08-04')).toBe(true)
    expect(isStartDateDisabled(new Date(2026, 6, 31), '2026-08-01', '2026-08-31', null)).toBe(true)
    expect(isStartDateDisabled(new Date(2026, 7, 4), '2026-08-01', '2026-08-31', '2026-08-04')).toBe(false)
  })

  it('disables end dates before the selected start date', () => {
    expect(isEndDateDisabled(new Date(2026, 7, 2), '2026-08-01', '2026-08-31', '2026-08-03')).toBe(true)
    expect(isEndDateDisabled(new Date(2026, 7, 3), '2026-08-01', '2026-08-31', '2026-08-03')).toBe(false)
  })

  it('returns a concrete validation message before submitting', () => {
    expect(validateDateRange('2026-08-06', '2026-08-05', '2026-08-01', '2026-08-31'))
      .toBe('截止日期不能早于开始日期')
    expect(validateDateRange('2026-07-31', '2026-08-05', '2026-08-01', '2026-08-31'))
      .toBe('日期必须在项目周期 2026-08-01 至 2026-08-31 内')
  })
})
