// Summary counts are integers; grouping them does not need cold Intl initialization.
export function formatCount(value: number) {
  return Number.isSafeInteger(value) && value >= 0 ? String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',') : '—'
}
