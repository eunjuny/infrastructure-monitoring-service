import { expect, it, vi } from 'vitest'
import { formatCount } from '../src/count'
it('groups integer resource counts without locale initialization', () => {
  const locale = vi.spyOn(Number.prototype, 'toLocaleString')
  expect([0, 20, 2000, 100000, 1000000000].map(formatCount)).toEqual(['0', '20', '2,000', '100,000', '1,000,000,000'])
  expect(locale).not.toHaveBeenCalled()
})
it('does not silently round invalid or unsafe counts', () => {
  expect([-1, 1.2, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1].map(formatCount)).toEqual(['—', '—', '—', '—', '—'])
})
