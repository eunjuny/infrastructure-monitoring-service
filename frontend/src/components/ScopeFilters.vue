<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { api, errorMessage, type Reference, type SearchResult } from '../api'
const props = defineProps<{ cluster: Reference | null; namespace: Reference | null }>()
const emit = defineEmits<{ change: [cluster: Reference | null, namespace: Reference | null] }>()
const clusters = shallowRef<SearchResult[]>([]), namespaces = shallowRef<SearchResult[]>([])
const open = ref(false), busy = ref(false), error = ref(''), term = ref(''), cursor = ref<string | null>(null)
let controller: AbortController | undefined, revision = 0, timer: ReturnType<typeof setTimeout> | undefined
async function loadNamespaces(next = false) {
  controller?.abort(); controller = new AbortController()
  const signal = controller.signal, token = ++revision
  busy.value = true; error.value = ''
  try {
    const result = await api.list({ type: 'NAMESPACE', q: term.value, status: '', ...(props.cluster ? { clusterId: props.cluster.id } : {}) }, next ? cursor.value : null, signal)
    if (token === revision) { namespaces.value = result.items; cursor.value = result.nextCursor }
  } catch (cause) { if (!signal.aborted && token === revision) error.value = errorMessage(cause) }
  finally { if (token === revision) busy.value = false }
}
async function toggle(event: Event) {
  open.value = (event.target as HTMLDetailsElement).open
  if (!open.value || clusters.value.length) return
  try { clusters.value = (await api.list({ type: 'CLUSTER', q: '', status: '' })).items; await loadNamespaces() }
  catch (cause) { error.value = errorMessage(cause) }
}
function changeCluster(event: Event) {
  const id = Number((event.target as HTMLSelectElement).value)
  emit('change', clusters.value.find(row => row.resource.id === id)?.resource || null, null)
}
function search() { clearTimeout(timer); timer = setTimeout(() => { void loadNamespaces() }, 250) }
watch(() => props.cluster?.id, () => { term.value = ''; clearTimeout(timer); if (open.value) void loadNamespaces() })
onBeforeUnmount(() => { controller?.abort(); revision++; clearTimeout(timer) })
</script>
<template>
  <details class="scope-filters" @toggle="toggle">
    <summary>조회 범위: {{ cluster?.name || '모든 Cluster' }} / {{ namespace?.name || '모든 Namespace' }}</summary>
    <div v-if="open" class="scope-options">
      <label>Cluster 범위<select aria-label="Cluster 범위" :value="cluster?.id || ''" @change="changeCluster"><option value="">모든 Cluster</option><option v-for="row in clusters" :key="row.resource.id" :value="row.resource.id">{{ row.resource.name }}</option></select></label>
      <label>Namespace 찾기<input v-model="term" maxlength="160" placeholder="이름으로 찾기" @input="search" /></label>
      <label>Namespace 범위<select aria-label="Namespace 범위" :value="namespace?.id || ''" :disabled="busy" @change="emit('change', cluster, namespaces.find(row => row.resource.id === Number(($event.target as HTMLSelectElement).value))?.resource || null)"><option value="">모든 Namespace</option><option v-if="namespace && !namespaces.some(row => row.resource.id === namespace?.id)" :value="namespace?.id">{{ namespace?.name }}</option><option v-for="row in namespaces" :key="row.resource.id" :value="row.resource.id">{{ row.resource.name }}</option></select></label>
      <button v-if="cursor" :disabled="busy" @click="loadNamespaces(true)">Namespace 다음 페이지</button>
      <button @click="emit('change', null, null)">범위 초기화</button>
    </div>
    <p v-if="busy" role="status">Namespace 조회 중…</p>
    <p v-if="error" role="alert" class="error">{{ error }} <button @click="loadNamespaces()">다시 조회</button></p>
  </details>
</template>
