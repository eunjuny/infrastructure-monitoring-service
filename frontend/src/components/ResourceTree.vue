<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { api, keyOf, errorMessage, type Page, type Resource, type Reference } from '../api'
import VirtualList from './VirtualList.vue'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ selected: string }>()
const emit = defineEmits<{ select: [resource: Resource]; browse: [resource: Resource] }>()
type Branch = { page: Page<Resource>; cursor: string | null; previous: (string | null)[]; pinned?: Reference }
type TreeRow = { kind: 'resource'; resource: Resource; depth: number } | { kind: 'page'; parent: Resource | null; depth: number; branch: Branch }
const branches = shallowRef(new Map<string, Branch>()), expanded = ref<string[]>([])
const loading = ref(false), error = ref('')
const virtual = ref<{ scrollToIndex(index: number): void } | null>(null)
let controller: AbortController | undefined, revision = 0
const branchKey = (r: Reference | null) => r ? keyOf(r) : 'root'
const rows = computed(() => {
  const result: TreeRow[] = []
  function walk(parent: Resource | null, depth: number) {
    const branch = branches.value.get(branchKey(parent))
    if (!branch) return
    for (const resource of branch.page.items) {
      result.push({ kind: 'resource', resource, depth })
      if (expanded.value.includes(keyOf(resource))) walk(resource, depth + 1)
    }
    if (branch.page.hasMore || branch.previous.length) result.push({ kind: 'page', parent, depth, branch })
  }
  walk(null, 0); return result
})
const rowKeys = computed(() => rows.value.map(row => row.kind === 'resource' ? keyOf(row.resource) : `page:${branchKey(row.parent)}`))
function begin() {
  controller?.abort(); controller = new AbortController(); loading.value = true; error.value = ''
  return { signal: controller.signal, token: ++revision }
}
function store(key: string, branch: Branch) {
  const next = new Map(branches.value); next.delete(key); next.set(key, branch)
  while (next.size > 24) {
    const removable = [...next.keys()].find(k => k !== 'root' && !expanded.value.includes(k))
    if (!removable) break
    next.delete(removable)
  }
  branches.value = next
}
function allowExpansion(key: string) {
  if (expanded.value.includes(key)) return
  expanded.value = [...expanded.value, key].slice(-8)
}
async function refresh() {
  const { signal, token } = begin()
  try {
    const targets = [...branches.value.entries()].filter(([key]) => key === 'root' || expanded.value.includes(key))
    if (!targets.length) targets.push(['root', { page: { items: [], hasMore: false, nextCursor: null }, cursor: null, previous: [] }])
    const replacement = await Promise.all(targets.map(async ([key, branch]) => {
      const [type, id] = key.split(':')
      const parent = key === 'root' ? null : { type, id: Number(id), name: '' } as Reference
      const page = await api.children(parent, branch.cursor, signal)
      if (branch.pinned && !page.items.some(item => keyOf(item) === keyOf(branch.pinned!))) {
        page.items = [(await api.detail(branch.pinned, signal)).resource, ...page.items]
      }
      return [key, { ...branch, page }] as const
    }))
    if (token === revision) branches.value = new Map(replacement)
  } catch (cause) { if (token === revision && !signal.aborted) error.value = errorMessage(cause) }
  finally { if (token === revision) loading.value = false }
}
async function toggle(resource: Resource) {
  const key = keyOf(resource)
  if (expanded.value.includes(key)) { expanded.value = expanded.value.filter(k => k !== key); return }
  if (!resource.hasChildren || loading.value) return
  if (branches.value.has(key)) { allowExpansion(key); return }
  const { signal, token } = begin()
  try {
    const page = await api.children(resource, null, signal)
    if (token !== revision) return
    allowExpansion(key); store(key, { page, cursor: null, previous: [] })
  } catch (cause) { if (token === revision && !signal.aborted) error.value = errorMessage(cause) }
  finally { if (token === revision) loading.value = false }
}
async function resetHierarchy() {
  const { signal, token } = begin()
  try {
    const page = await api.children(null, null, signal)
    if (token !== revision) return
    branches.value = new Map([['root', { page, cursor: null, previous: [] }]])
    expanded.value = []; await nextTick(); virtual.value?.scrollToIndex(0)
  } catch (cause) { if (token === revision && !signal.aborted) error.value = errorMessage(cause) }
  finally { if (token === revision) loading.value = false }
}
async function pageBranch(row: Extract<TreeRow, { kind: 'page' }>, backwards: boolean) {
  if (loading.value) return
  const previous = [...row.branch.previous]
  const cursor = backwards ? previous.pop() ?? null : row.branch.page.nextCursor
  if (!backwards) previous.push(row.branch.cursor)
  const { signal, token } = begin()
  try {
    const page = await api.children(row.parent, cursor, signal)
    if (token !== revision) return
    expanded.value = expanded.value.filter(k => !row.branch.page.items.some(item => keyOf(item) === k))
    store(branchKey(row.parent), { page, cursor, previous: previous.slice(-10) })
  } catch (cause) { if (token === revision && !signal.aborted) error.value = errorMessage(cause) }
  finally { if (token === revision) loading.value = false }
}
async function reveal(path: Reference[]) {
  const { signal, token } = begin()
  try {
    const replacement = new Map<string, Branch>()
    for (let index = 0; index < path.length; index++) {
      const parent = index ? path[index - 1]! : null
      const page = await api.children(parent, null, signal)
      const target = path[index]!
      // The matched ID may be outside page one: pin only that verified resource.
      const pinned = !page.items.some(item => keyOf(item) === keyOf(target))
      if (pinned) page.items = [(await api.detail(target, signal)).resource, ...page.items]
      replacement.set(branchKey(parent), { page, cursor: null, previous: [], pinned: pinned ? target : undefined })
    }
    if (token !== revision) return
    branches.value = replacement; expanded.value = path.slice(0, -1).map(keyOf)
    await nextTick()
    const target = path.at(-1)
    if (target) virtual.value?.scrollToIndex(rows.value.findIndex(row => row.kind === 'resource' && keyOf(row.resource) === keyOf(target)))
  } catch (cause) { if (token === revision && !signal.aborted) error.value = errorMessage(cause) }
  finally { if (token === revision) loading.value = false }
}
onMounted(refresh)
onBeforeUnmount(() => { controller?.abort(); revision++ })
function stop() { controller?.abort(); revision++; loading.value = false }
defineExpose({ refresh, reveal, stop, isBusy: () => loading.value, hasError: () => Boolean(error.value) })
</script>
<template>
  <section class="panel tree-panel" aria-labelledby="tree-heading">
    <div class="panel-title"><h2 id="tree-heading">계층 탐색</h2><span v-if="loading" role="status">조회 중</span><button :disabled="loading" @click="resetHierarchy">처음</button></div>
    <p class="muted">Cluster → Node → Namespace → Pod</p>
    <p v-if="error" role="alert" class="error">{{ error }} <button @click="refresh">다시 조회</button></p>
    <p v-if="!loading && !error && !rows.length" class="empty">저장된 리소스가 없습니다.</p>
    <VirtualList ref="virtual" :items="rows" :keys="rowKeys" label="리소스 계층 목록" :row-height="44">
      <template #default="{ item }">
        <div v-if="item.kind === 'resource'" class="tree-item" :class="{ selected: props.selected === keyOf(item.resource) }" :style="{ paddingLeft: `${item.depth * 14}px` }">
          <button class="expand" :aria-label="`${item.resource.name} ${expanded.includes(keyOf(item.resource)) ? '접기' : '펼치기'}`"
            :aria-expanded="item.resource.hasChildren ? expanded.includes(keyOf(item.resource)) : undefined"
            :disabled="!item.resource.hasChildren || loading" @click="toggle(item.resource)">{{ item.resource.hasChildren ? expanded.includes(keyOf(item.resource)) ? '−' : '+' : '·' }}</button>
          <UiIcon class="tree-kind" :name="item.resource.type" /><button class="tree-name" :title="item.resource.name" @click="emit('select', item.resource)">{{ item.resource.name }}</button>
          <button v-if="item.resource.hasChildren" class="browse" :aria-label="`${item.resource.name} 하위 목록 보기`" @click="emit('browse', item.resource)">→</button>
        </div>
        <div v-else class="tree-pages" :style="{ paddingLeft: `${item.depth * 14}px` }">
          <button v-if="item.branch.previous.length" :disabled="loading" @click="pageBranch(item, true)">이전</button>
          <button v-if="item.branch.page.hasMore" :disabled="loading" @click="pageBranch(item, false)">다음 하위 항목</button>
        </div>
      </template>
    </VirtualList>
  </section>
</template>
