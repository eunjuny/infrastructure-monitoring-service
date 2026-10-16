<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { api, errorMessage, type Operations } from '../api'
import { usePolling } from '../usePolling'
import OperationsAlerts from './OperationsAlerts.vue'
const data = shallowRef<Operations | null>(null), busy = ref(false), error = ref('')
let controller: AbortController | undefined
const metrics = [
  { key: 'up', title: '수집 대상 상태', unit: '', scale: 1 },
  { key: 'requests', title: '리소스 API 요청 추정 / 5분', unit: '회', scale: 1 },
  { key: 'apiP95', title: 'API p95 / 5분', unit: 'ms', scale: 1000 },
  { key: 'queryP95', title: 'DB 조회 p95 / 5분', unit: 'ms', scale: 1000 },
  { key: 'errors', title: 'API 5xx / 5분', unit: '회', scale: 1 },
  { key: 'heap', title: 'JVM heap 사용', unit: 'MiB', scale: 1 / 1048576 },
  { key: 'dbActive', title: 'DB 사용 중 연결', unit: '개', scale: 1 },
  { key: 'mockChanged', title: 'Mock 변경 행 추정 / 5분', unit: '행', scale: 1 },
  { key: 'mockFailures', title: 'Mock 실패 / 5분', unit: '회', scale: 1 },
  { key: 'mockP95', title: 'Mock 배치 p95 / 5분', unit: 'ms', scale: 1000 },
]
function display(key: string, scale: number) {
  const value = data.value?.values[key]
  return value == null || !Number.isFinite(value) ? '표본 없음' : (value * scale).toLocaleString('ko-KR', { maximumFractionDigits: 1 })
}
function lines(key: string) {
  const points = data.value?.series[key] || [], maximum = Math.max(.001, ...points.map(point => point.value || 0))
  const start = Date.parse(data.value?.fetchedAt || '') / 1000 - 1800
  const groups: string[][] = [[]]
  points.forEach((point, index) => {
    if (point.value == null) { groups.push([]); return }
    groups.at(-1)!.push(`${Math.max(0, Math.min(580, (point.timestamp - start) / 1800 * 580))},${140 - point.value / maximum * 120}`)
  })
  return groups.filter(group => group.length > 1).map(group => group.join(' '))
}
function chartMaximum(key: string) { return Math.max(0, ...(data.value?.series[key] || []).map(point => point.value || 0)) * 1000 }
async function load() {
  controller?.abort(); controller = new AbortController(); busy.value = true; error.value = ''
  const signal = controller.signal
  try { const result = await api.operations(signal); if (!signal.aborted) data.value = result; return result.available }
  catch (cause) { if (!signal.aborted) error.value = errorMessage(cause); return false }
  finally { if (!signal.aborted) busy.value = false }
}
const polling = usePolling(load, () => !busy.value, () => { controller?.abort(); busy.value = false }, 15000)
onMounted(() => { void load(); polling.start() })
onBeforeUnmount(() => { controller?.abort(); polling.dispose() })
</script>
<template>
  <section class="operations-dashboard">
    <div class="page-header"><div><p class="eyebrow">OPERATIONS</p><h1>서비스 운영 관측</h1><p>Prometheus에서 조회한 실제 API·DB·JVM·Mock 배치 지표입니다. Mock Node 사용률과 다른 데이터입니다.</p></div><button :disabled="busy" @click="load">지표 새로고침</button></div>
    <OperationsAlerts />
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="busy" role="status">Prometheus 지표 조회 중…</p>
    <p v-if="data && !data.available" role="alert" class="error">{{ data.message }}</p>
    <template v-if="data?.available">
      <p class="scope-note">출처 {{ data.source }} · 조회 {{ new Date(data.fetchedAt).toLocaleTimeString('ko-KR') }} · 표본이 없으면 0으로 대체하지 않습니다.</p>
      <dl class="operation-cards"><div v-for="metric in metrics" :key="metric.key"><dt>{{ metric.title }}</dt><dd>{{ display(metric.key, metric.scale) }} <small>{{ metric.unit }}</small></dd></div></dl>
      <div class="operation-charts"><section v-for="chart in [{ key: 'apiP95', title: 'API p95 추이' }, { key: 'queryP95', title: 'DB 조회 p95 추이' }]" :key="chart.key" class="panel"><h2>{{ chart.title }}</h2><p>최근 30분 · 각 시점 직전 5분 구간</p><small>세로 0–{{ chartMaximum(chart.key).toFixed(1) }}ms · 가로 30분 전–최근</small><svg v-if="lines(chart.key).length" viewBox="0 0 600 160" role="img" :aria-label="chart.title"><path d="M0 140H580" stroke="#cbd5e1" /><polyline v-for="(line, index) in lines(chart.key)" :key="index" :points="line" fill="none" stroke="#2563eb" stroke-width="2" /></svg><p v-else>추이 표본이 아직 부족합니다. 수집이 진행되면 표시됩니다.</p></section></div>
    </template>
  </section>
</template>
