<script setup lang="ts">
import { defineAsyncComponent, nextTick, ref, watchEffect } from 'vue'
import { childType, keyOf, type Detail, type Reference, type Resource } from '../api'
import { fullTimestamp } from '../timestamp'
import UiIcon from './UiIcon.vue'
import ResourceDiagnosticsPanel from './ResourceDiagnosticsPanel.vue'
const ResourceEvents = defineAsyncComponent(() => import('./ResourceEvents.vue'))
const KubernetesEvents = defineAsyncComponent(() => import('./KubernetesEvents.vue'))
const { selected, detail, busy, error } = defineProps<{ selected: Reference; detail: Detail | null; busy: boolean; error: string }>()
const emit = defineEmits<{ select: [resource: Reference]; browse: [resource: Resource]; close: []; reveal: [] }>()
const tab = ref<'overview' | 'path'>('overview'), showEvents = ref(false), showKubernetesEvents = ref(false)
const overviewTab = ref<HTMLButtonElement | null>(null), pathTab = ref<HTMLButtonElement | null>(null)
const typeLabels = { CLUSTER: 'Cluster', NODE: 'Node', NAMESPACE: 'Namespace', POD: 'Pod' } as const
const statusLabels = { NORMAL: '정상', WARNING: '주의', ERROR: '오류' } as const
watchEffect(() => { if (busy && !detail) { tab.value = 'overview'; showEvents.value = false; showKubernetesEvents.value = false } })
function switchTab(next: 'overview' | 'path', focus = false) {
  tab.value = next
  if (focus) void nextTick(() => { (next === 'overview' ? overviewTab.value : pathTab.value)?.focus() })
}
function metric(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : null }
function attributeName(key: string) { return ({ cpuUsage: 'CPU 사용률 (%)', memoryUsage: '메모리 사용률 (%)', restartCount: '재시작 횟수' } as Record<string, string>)[key] || key }
</script>
<template>
  <aside class="panel detail-panel" aria-labelledby="detail-heading">
    <div class="panel-title"><h2 id="detail-heading">리소스 상세</h2><span v-if="busy" role="status">조회 중</span><button class="icon-button" aria-label="상세 닫기" @click="emit('close')"><UiIcon name="close" /></button></div>
    <p v-if="error" role="alert" class="error">{{ error }} <button @click="emit('select', selected)">다시 조회</button></p>
    <template v-if="detail">
      <div class="detail-kind"><UiIcon :name="detail.resource.type" /><span>{{ typeLabels[detail.resource.type] }}</span></div><h3>{{ detail.resource.name }}</h3>
      <span class="badge" :class="detail.resource.status.toLowerCase()">{{ statusLabels[detail.resource.status] }}</span>
      <div class="detail-tabs" role="tablist" aria-label="상세 정보 탭"><button ref="overviewTab" id="overview-tab" role="tab" :tabindex="tab === 'overview' ? 0 : -1" :aria-selected="tab === 'overview'" aria-controls="overview-panel" @click="switchTab('overview')" @keydown.right.prevent="switchTab('path', true)" @keydown.left.prevent="switchTab('path', true)">개요</button><button ref="pathTab" id="path-tab" role="tab" :tabindex="tab === 'path' ? 0 : -1" :aria-selected="tab === 'path'" aria-controls="path-panel" @click="switchTab('path')" @keydown.right.prevent="switchTab('overview', true)" @keydown.left.prevent="switchTab('overview', true)">관계·경로</button></div>
      <div id="overview-panel" role="tabpanel" aria-labelledby="overview-tab" v-show="tab === 'overview'">
        <div v-if="detail.resource.type === 'NODE'" class="metric-bars"><div v-for="metricKey in ['cpuUsage', 'memoryUsage']" :key="metricKey"><div><span>{{ attributeName(metricKey) }}</span><strong>{{ metric(detail.attributes[metricKey]) ?? '—' }}%</strong></div><meter v-if="metric(detail.attributes[metricKey]) !== null" :value="metric(detail.attributes[metricKey])!" min="0" max="100" :aria-label="attributeName(metricKey)" /></div></div>
        <dl class="attributes"><div><dt>리소스 ID</dt><dd>{{ detail.resource.id }}</dd></div><div><dt>상태 갱신 시각</dt><dd>{{ fullTimestamp(detail.resource.updatedAt) }}</dd></div></dl>
        <ResourceDiagnosticsPanel :type="detail.resource.type" :attributes="detail.attributes" />
      </div>
      <div id="path-panel" role="tabpanel" aria-labelledby="path-tab" v-show="tab === 'path'">
        <p class="muted">{{ detail.path.length }}단계 경로 · 항목을 선택해 상세로 이동합니다.</p>
        <div class="relationship-cards"><button v-for="reference in detail.path" :key="keyOf(reference)" @click="emit('select', reference)"><UiIcon :name="reference.type" /><span><small>{{ typeLabels[reference.type] }} · ID {{ reference.id }}</small><strong>{{ reference.name }}</strong></span><UiIcon name="chevron" /></button></div>
      </div>
      <div class="detail-path"><p class="section-caption">리소스 경로</p><nav aria-label="상위 리소스 경로" class="breadcrumbs"><button v-for="reference in detail.path" :key="keyOf(reference)" @click="emit('select', reference)"><UiIcon :name="reference.type" /><span>{{ reference.name }}</span></button></nav></div>
      <button class="reveal-button" @click="emit('reveal')"><UiIcon name="layout" />계층에서 위치 보기</button>
      <button v-if="detail.resource.hasChildren" class="primary child-button" @click="emit('browse', detail.resource)">하위 {{ childType(detail.resource.type) }} 목록 보기<UiIcon name="arrow" /></button>
      <button class="reveal-button" :aria-expanded="showEvents" @click="showEvents = !showEvents">{{ showEvents ? '변경 이력 접기' : '변경 이력 보기' }}</button>
      <ResourceEvents v-if="showEvents" :resource="detail.resource" />
      <button class="reveal-button" :aria-expanded="showKubernetesEvents" @click="showKubernetesEvents = !showKubernetesEvents">{{ showKubernetesEvents ? '이벤트 접기' : '이벤트 보기' }}</button>
      <KubernetesEvents v-if="showKubernetesEvents" :resource="detail.resource" />
    </template>
  </aside>
</template>
