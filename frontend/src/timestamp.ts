// Fixed, readable local-time display avoids cold Intl/ICU initialization in the first screen.
const pad = (value: number) => String(value).padStart(2, '0')
function parsed(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}
function clock(date: Date) {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
export function shortTimestamp(value: string) {
  const date = parsed(value)
  return date ? clock(date) : '—'
}
export function fullTimestamp(value: string) {
  const date = parsed(value)
  return date ? `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${clock(date)}` : '—'
}
