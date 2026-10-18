<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api, errorMessage, keyOf, type KubernetesEvent, type Reference } from '../api'
const props = defineProps<{ resource: Reference }>()
const rows = ref<KubernetesEvent[]>([]), busy = ref(false), error = ref('')
const formatter = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium' })
const displayMessage = (value: string) => value === 'Mock resource is in a non-normal state.'
  ? '리소스가 비정상 상태입니다.' : value
let request: AbortController | undefined, revision = 0
async function load() {
  request?.abort(); request = new AbortController()
  const current = ++revision, signal = request.signal
  busy.value = true; error.value = ''
  try { const value = await api.kubernetesEvents(props.resource, signal); if (current === revision) rows.value = value }
  catch (cause) { if (!signal.aborted && current === revision) error.value = errorMessage(cause) }
  finally { if (current === revision) busy.value = false }
}
watch(() => keyOf(props.resource), () => { rows.value = []; void load() }, { immediate: true })
onBeforeUnmount(() => { request?.abort(); revision++ })
</script>

<template>
  <section class="event-history" aria-label="리소스 이벤트">
    <div class="panel-title"><h4>리소스 이벤트</h4><button :disabled="busy" @click="load">새로고침</button></div>
    <p v-if="busy" role="status">이벤트 조회 중…</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="!busy && !error && rows.length === 0">현재 표시할 이벤트가 없습니다.</p>
    <ol v-if="rows.length"><li v-for="event in rows" :key="event.id">
      <time>{{ formatter.format(new Date(event.lastSeenAt)) }}</time>
      <strong>{{ event.reason }} · {{ event.type === 'WARNING' ? '주의' : '정보' }}</strong>
      <p>{{ displayMessage(event.message) }}</p><small>최초 {{ formatter.format(new Date(event.firstSeenAt)) }} · {{ event.occurrenceCount }}회</small>
    </li></ol>
  </section>
</template>
