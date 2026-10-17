<script setup lang="ts">
import { computed } from 'vue'
import type { ResourceType, ResourceAttributes } from '../api'

const props = defineProps<{ type: ResourceType; attributes: ResourceAttributes }>()
const count = (key: string) => {
  const value = props.attributes[key]
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null
}
const text = (key: string) => typeof props.attributes[key] === 'string' ? props.attributes[key] as string : null
const translations: Record<string, string> = {
  Running: '실행 중', Pending: '시작 대기', Failed: '실패', Waiting: '대기', Terminated: '종료',
  KubeletReady: '노드 준비 완료', KubeletNotReady: '노드 준비되지 않음',
  MemoryPressure: '메모리 압박', DiskPressure: '디스크 압박',
  ImagePullBackOff: '이미지 가져오기 재시도', ReadinessProbeFailed: '준비 상태 검사 실패',
  CrashLoopBackOff: '컨테이너 반복 종료·재시작 대기', OOMKilled: '메모리 부족으로 종료', Error: '오류 종료',
}
function translated(key: string) {
  const value = text(key)
  return value === null ? '없음' : translations[value] ? `${value} · ${translations[value]}` : value
}
const fields = computed(() => {
  const data = props.attributes
  const rows: { label: string; value: string }[] = []
  const add = (key: string, label: string, value: string) => { if (key in data) rows.push({ label, value }) }
  if (props.type === 'NODE') {
    add('ready', 'Ready 상태', typeof data.ready === 'boolean' ? data.ready ? 'Ready' : 'NotReady' : '—')
    for (const [key, label] of [['memoryPressure', 'MemoryPressure'], ['diskPressure', 'DiskPressure']]) {
      add(key!, label!, typeof data[key!] === 'boolean' ? data[key!] ? '감지' : '없음' : '—')
    }
    add('cpuCapacityCores', 'CPU 용량', count('cpuCapacityCores') === null ? '—' : `${count('cpuCapacityCores')} 코어`)
    add('memoryCapacityMiB', '메모리 용량', count('memoryCapacityMiB') === null ? '—' : `${count('memoryCapacityMiB')! / 1024} GiB`)
    add('conditionReason', '상태 사유', translated('conditionReason'))
  }
  if (props.type === 'POD') {
    add('phase', 'Pod 실행 단계', translated('phase'))
    const ready = count('readyContainerCount'), total = count('containerCount')
    add('containerCount', '컨테이너 준비', ready === null || total === null || total < 1 || ready > total ? '—' : `${ready} / ${total}`)
    add('containerState', '진단 대상 컨테이너 상태', translated('containerState'))
    add('failureReason', '현재 문제 사유', translated('failureReason'))
    add('lastTerminationReason', '최근 종료 원인', translated('lastTerminationReason'))
    add('restartCount', '재시작 횟수', count('restartCount') === null ? '—' : `${count('restartCount')} 회`)
    add('image', '대표 컨테이너 이미지', text('image') || '—')
    add('workloadKind', '워크로드 종류', text('workloadKind') || '—')
    add('workloadName', '워크로드 이름', text('workloadName') || '—')
  }
  return rows
})
const summaries = computed(() => props.type === 'CLUSTER' ? [
  { label: '준비된 Node', value: count('readyNodeCount'), total: count('nodeCount') },
  { label: '문제 Pod', value: count('problemPodCount'), total: count('podCount') },
] : props.type === 'NAMESPACE' ? [
  { label: '전체 Pod', value: count('podCount'), total: null },
  { label: '오류 Pod', value: count('errorPodCount'), total: null },
  { label: '준비되지 않은 Pod', value: count('notReadyPodCount'), total: null },
] : [])
</script>

<template>
  <section v-if="fields.length || summaries.some(item => item.value !== null)" class="resource-diagnostics" aria-label="리소스 진단 정보">
    <div class="diagnostic-heading"><h4>리소스 진단</h4><small v-if="attributes.diagnosticSource === 'MOCK'">Mock</small></div>
    <template v-if="summaries.some(item => item.value !== null)">
      <p class="muted diagnostic-scope">선택한 {{ type === 'CLUSTER' ? 'Cluster' : 'Namespace' }}의 전체 하위 리소스 기준</p>
      <dl class="diagnostic-summary">
        <div v-for="item in summaries" :key="item.label"><dt>{{ item.label }}</dt><dd>{{ item.value ?? '—' }}<small v-if="item.total !== null"> / {{ item.total }}</small></dd></div>
      </dl>
    </template>
    <dl v-if="fields.length" class="attributes"><div v-for="field in fields" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></div></dl>
  </section>
</template>

<style scoped>
.diagnostic-heading { display: flex; align-items: center; justify-content: space-between; margin-top: 1rem; }
.diagnostic-heading h4 { margin: 0; }
.diagnostic-heading small { color: #64748b; }
.diagnostic-scope { font-size: .75rem; }
.diagnostic-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .5rem; }
.diagnostic-summary > div { background: #f8fafc; padding: .75rem; border-radius: .5rem; }
.diagnostic-summary dt { font-size: .75rem; color: #475569; }
.diagnostic-summary dd { margin: .4rem 0 0; font-size: 1.25rem; font-weight: 700; }
.diagnostic-summary dd small { font-size: .8rem; font-weight: 400; }
.attributes dd { overflow-wrap: anywhere; }
</style>
