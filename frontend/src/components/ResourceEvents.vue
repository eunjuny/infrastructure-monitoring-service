<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { api, errorMessage, keyOf, type Reference, type EventPage } from '../api'
const props = defineProps<{ resource: Reference }>()
const page = shallowRef<EventPage | null>(null), busy = ref(false), error = ref('')
let controller: AbortController | undefined, revision = 0
const formatter = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'medium' })
const status = (value: string) => ({ NORMAL: '정상', WARNING: '주의', ERROR: '오류' }[value] || value)
const attribute = (value: string) => ({ cpuUsage: 'CPU (%)', memoryUsage: '메모리 (%)', restartCount: '재시작 수' }[value] || value)
async function load(next = false) {
  controller?.abort(); controller = new AbortController()
  const signal = controller.signal, token = ++revision
  busy.value = true; error.value = ''
  try { const result = await api.events(props.resource, next ? page.value?.nextBeforeId : null, signal); if (token === revision) page.value = result }
  catch (cause) { if (!signal.aborted && token === revision) error.value = errorMessage(cause) }
  finally { if (token === revision) busy.value = false }
}
watch(() => keyOf(props.resource), () => { page.value = null; void load() }, { immediate: true })
onBeforeUnmount(() => { controller?.abort(); revision++ })
</script>
<template>
  <section class="event-history" aria-label="리소스 상태 변경 이력">
    <div class="panel-title"><h4>상태 변경 이력</h4><button :disabled="busy" @click="load()">이력 새로고침</button></div>
    <p v-if="busy" role="status">이력 조회 중…</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <template v-if="page">
      <small>시뮬레이터 변경만 기록 · 최근 {{ page.retentionHours }}시간 / 전체 {{ page.maximumEvents.toLocaleString() }}건 기준 정리</small>
      <p v-if="!page.items.length && !busy">보관 중인 변경 이력이 없습니다. 이력 수집 도입 이전이나 보존 범위 밖의 변경은 표시되지 않습니다.</p>
      <ol><li v-for="event in page.items" :key="event.id"><time>{{ formatter.format(new Date(event.occurredAt)) }}</time><strong>{{ status(event.previousStatus) }} → {{ status(event.nextStatus) }}</strong><small>Mock 시뮬레이터 상태 전이</small><dl><div v-for="(value, key) in event.afterAttributes" :key="key"><dt>{{ attribute(key) }}</dt><dd>{{ event.beforeAttributes[key] }} → {{ value }}</dd></div></dl></li></ol>
      <button v-if="page.hasMore" :disabled="busy" @click="load(true)">이전 이력 20개</button><button v-if="page.items.length" :disabled="busy" @click="load()">최신 이력</button>
    </template>
  </section>
</template>
