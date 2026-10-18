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
]
const simulatorMetrics = [
  { key: 'mockChanged', title: '상태 갱신 행 추정 / 5분', unit: '행', scale: 1 },
  { key: 'mockFailures', title: '상태 갱신 실패 / 5분', unit: '회', scale: 1 },
  { key: 'mockP95', title: '상태 갱신 배치 p95 / 5분', unit: 'ms', scale: 1000 },
]
function display(key: string, scale: number) {
  const value = data.value?.values[key]
  if (value == null || !Number.isFinite(value)) return '표본 없음'
  if (key === 'up') {
    if (value === 1) return '수집 성공'
    if (value === 0) return '수집 실패'
    return '확인 불가'
  }
  return (value * scale).toLocaleString('ko-KR', { maximumFractionDigits: 1 })
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
    <div class="page-header"><h1>서비스 운영 관측</h1><button :disabled="busy" @click="load">지표 새로고침</button></div>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="busy" role="status">Prometheus 지표 조회 중…</p>
    <p v-if="data && !data.available" role="alert" class="error">{{ data.message }}</p>
    <template v-if="data?.available">
      <p class="scope-note operations-meta">{{ data.source }} · 최근 조회 {{ new Date(data.fetchedAt).toLocaleTimeString('ko-KR') }}</p>
      <dl class="operation-cards" aria-label="서비스 운영 지표"><div v-for="metric in metrics" :key="metric.key"><dt>{{ metric.title }}</dt><dd>{{ display(metric.key, metric.scale) }} <small v-if="metric.unit && data.values[metric.key] != null && Number.isFinite(data.values[metric.key])">{{ metric.unit }}</small></dd></div></dl>
      <div class="operation-charts"><section v-for="chart in [{ key: 'apiP95', title: 'API p95 추이' }, { key: 'queryP95', title: 'DB 조회 p95 추이' }]" :key="chart.key" class="panel"><div class="chart-heading"><h2>{{ chart.title }}</h2><span>최근 30분 · 5분 구간 p95</span></div><template v-if="lines(chart.key).length"><small>0–{{ chartMaximum(chart.key).toFixed(1) }}ms</small><svg viewBox="0 0 600 160" role="img" :aria-label="chart.title"><path d="M0 140H580" stroke="#cbd5e1" /><polyline v-for="(line, index) in lines(chart.key)" :key="index" :points="line" fill="none" stroke="#2563eb" stroke-width="2" /></svg><div class="chart-time-axis"><span>30분 전</span><span>최근</span></div></template><p v-else class="chart-empty">최근 30분간 추이 표본 없음</p></section></div>
      <details class="simulator-metrics panel"><summary>상태 갱신 배치 지표</summary><dl class="operation-cards" aria-label="상태 갱신 배치 지표"><div v-for="metric in simulatorMetrics" :key="metric.key"><dt>{{ metric.title }}</dt><dd>{{ display(metric.key, metric.scale) }} <small v-if="data.values[metric.key] != null && Number.isFinite(data.values[metric.key])">{{ metric.unit }}</small></dd></div></dl></details>
    </template>
    <OperationsAlerts />
  </section>
</template>
