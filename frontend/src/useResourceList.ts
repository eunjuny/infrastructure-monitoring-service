import { ref, shallowRef } from 'vue'
import { api, errorMessage, keyOf, type Scope, type SearchResult } from './api'
export const PAGE_LIMIT = 100, WINDOW_PAGES = 5
type LoadedPage = { cursor: string | null; items: SearchResult[]; next: string | null }
export function useResourceList() {
  const rows = shallowRef<SearchResult[]>([])
  const busy = ref(false), error = ref(''), offset = ref(0), hasMore = ref(false)
  let pages: LoadedPage[] = [], scope: Scope = { type: 'CLUSTER', q: '', status: '' }
  let controller: AbortController | undefined, revision = 0
  function start() {
    controller?.abort(); controller = new AbortController()
    busy.value = true; error.value = ''
    return { token: ++revision, signal: controller.signal }
  }
  function publish() {
    // Stable objects keep unchanged rows intact during periodic refresh.
    const previous = new Map(rows.value.map(row => [keyOf(row.resource), row]))
    rows.value = pages.flatMap(page => page.items).map(row => {
      const old = previous.get(keyOf(row.resource))
      return old && JSON.stringify(old) === JSON.stringify(row) ? old : row
    })
    hasMore.value = Boolean(pages.at(-1)?.next)
  }
  async function reset(nextScope: Scope) {
    scope = { ...nextScope }
    const { token, signal } = start()
    // Never display old rows as if they matched new filters.
    pages = []; rows.value = []; offset.value = 0; hasMore.value = false
    try {
      const page = await api.list(scope, null, signal)
      if (token !== revision) return
      pages = [{ cursor: null, items: page.items, next: page.hasMore ? page.nextCursor : null }]; publish()
    } catch (cause) { if (token === revision && !signal.aborted) error.value = errorMessage(cause) }
    finally { if (token === revision) busy.value = false }
  }
  async function next(): Promise<number> {
    const cursor = pages.at(-1)?.next
    if (busy.value || !cursor || error.value) return 0
    const { token, signal } = start()
    try {
      const page = await api.list(scope, cursor, signal)
      if (token !== revision) return 0
      pages.push({ cursor, items: page.items, next: page.hasMore ? page.nextCursor : null })
      const removed = pages.length > WINDOW_PAGES ? pages.shift()!.items.length : 0
      offset.value += removed; publish(); return removed
    } catch (cause) { if (token === revision && !signal.aborted) error.value = errorMessage(cause); return 0 }
    finally { if (token === revision) busy.value = false }
  }
  async function refresh() {
    if (!pages.length) return reset(scope)
    const { token, signal } = start()
    const replacement: LoadedPage[] = []
    let cursor = pages[0]!.cursor
    try {
      // Swap the current window atomically instead of emptying the visible list.
      for (let index = 0; index < pages.length; index++) {
        const page = await api.list(scope, cursor, signal)
        if (token !== revision) return
        replacement.push({ cursor, items: page.items, next: page.hasMore ? page.nextCursor : null })
        if (!page.hasMore || !page.nextCursor) break
        cursor = page.nextCursor
      }
      pages = replacement; publish()
    } catch (cause) { if (token === revision && !signal.aborted) error.value = errorMessage(cause) }
    finally { if (token === revision) busy.value = false }
  }
  function stop(clear = false) {
    controller?.abort(); revision++; busy.value = false
    if (clear) { pages = []; rows.value = []; offset.value = 0; hasMore.value = false; error.value = '' }
  }
  return { rows, busy, error, offset, hasMore, reset, next, refresh, stop }
}
