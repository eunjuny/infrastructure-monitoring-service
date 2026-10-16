import { expect, it, vi } from 'vitest'
import { fullTimestamp, shortTimestamp } from '../src/timestamp'

it('formats local dates with padded numeric fields and no Intl formatter', () => {
  const intl = vi.spyOn(Intl, 'DateTimeFormat')
  const value = new Date(2026, 0, 2, 3, 4, 5).toISOString()
  expect(shortTimestamp(value)).toBe('03:04:05')
  expect(fullTimestamp(value)).toBe('2026-01-02 03:04:05')
  expect(intl).not.toHaveBeenCalled()
})
it('formats midnight as 00 instead of a locale-dependent 24', () => {
  const value = new Date(2026, 8, 1, 0, 0, 0).toISOString()
  expect(fullTimestamp(value)).toBe('2026-09-01 00:00:00')
})
it('preserves input timestamps and handles invalid values safely', () => {
  const value = 'invalid'
  expect(shortTimestamp(value)).toBe('—')
  expect(fullTimestamp(value)).toBe('—')
  expect(value).toBe('invalid')
})
