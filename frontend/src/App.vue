<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { api, childType, keyOf, types, errorMessage, type Detail, type Reference, type Resource, type ResourceType, type ResourceStatus, type Scope, type Summary } from './api'
import { useResourceList } from './useResourceList'
import { usePolling } from './usePolling'
import ResourceTree from './components/ResourceTree.vue'
import VirtualList from './components/VirtualList.vue'
import UiIcon from './components/UiIcon.vue'
const summary = shallowRef<Summary | null>(null), summaryError = ref('')
const selected = shallowRef<Reference | null>(null), detail = shallowRef<Detail | null>(null)
const detailBusy = ref(false), detailError = ref(''), refreshing = ref(false), refreshedAt = ref('')
const query = ref(''), type = ref<ResourceType | ''>('POD'), status = ref<ResourceStatus | ''>('')
const parent = shallowRef<Resource | null>(null)
const detailTab = ref<'overview' | 'path'>('overview')
const hierarchyVisible = ref(false)
const overviewTab = ref<HTMLButtonElement | null>(null), pathTab = ref<HTMLButtonElement | null>(null)
const searchField = ref<HTMLInputElement | null>(null)
const summaryKeys = { CLUSTER: 'clusters', NODE: 'nodes', NAMESPACE: 'namespaces', POD: 'pods' } as const
const typeLabels = { CLUSTER: 'Cluster', NODE: 'Node', NAMESPACE: 'Namespace', POD: 'Pod' } as const
const typeDescriptions = { CLUSTER: '클러스터', NODE: '컴퓨팅 노드', NAMESPACE: '네임스페이스', POD: '워크로드' } as const
const list = useResourceList()
const tree = ref<InstanceType<typeof ResourceTree> | null>(null)
const virtual = ref<{ reset(): void; discardRows(count: number): void } | null>(null)
const keys = computed(() => list.rows.value.map(item => keyOf(item.resource)))
const selectedKey = computed(() => selected.value ? keyOf(selected.value) : '')
const windowStatuses = computed(() => {
  const counts = { NORMAL: 0, WARNING: 0, ERROR: 0 }
  for (const row of list.rows.value) counts[row.resource.status]++
  return counts
})
const currentScopeLabel = computed(() => parent.value && !query.value.trim() ? parent.value.name : '모든 부모 리소스')
const timeFormatter = new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
const dateFormatter = new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' })
let timer: ReturnType<typeof setTimeout> | undefined
let detailController: AbortController | undefined, summaryController: AbortController | undefined
let detailRevision = 0, appliedScope = ''
const scope = (): Scope => ({ type: query.value.trim() ? type.value : type.value || 'POD', q: query.value, status: status.value, parentId: query.value.trim() ? undefined : parent.value?.id })
async function applyFilters() {
  if (!query.value.trim() && !type.value) type.value = 'POD'
  clearTimeout(timer); timer = undefined; virtual.value?.reset()
  const next = scope(); appliedScope = JSON.stringify(next)
  await list.reset(next)
}
function searchInput() {
  // Immediately invalidate stale requests, even while debounce is pending.
  list.stop(true); clearTimeout(timer)
  timer = setTimeout(() => { timer = undefined; parent.value = null; void applyFilters() }, 250)
}
function changeType() { parent.value = null; void applyFilters() }
function changeStatus() { void applyFilters() }
function navigateType(nextType: ResourceType) {
  closeDetail()
  type.value = nextType; parent.value = null; query.value = ''; status.value = ''
  void applyFilters()
}
function clearFilters() { parent.value = null; query.value = ''; status.value = ''; void applyFilters() }
function closeDetail() {
  detailController?.abort(); detailRevision++; selected.value = null; detail.value = null
  detailError.value = ''; detailBusy.value = false; detailTab.value = 'overview'
}
function switchDetailTab(nextTab: 'overview' | 'path', focus = false) {
  detailTab.value = nextTab
  if (focus) void nextTick(() => { (nextTab === 'overview' ? overviewTab.value : pathTab.value)?.focus() })
}
function shortcut(event: KeyboardEvent) {
  const target = event.target instanceof HTMLElement ? event.target : null
  if (target?.closest('input, textarea, select, [contenteditable="true"]') || event.ctrlKey || event.metaKey || event.altKey) return
  if (event.key === '/') { event.preventDefault(); searchField.value?.focus() }
  if (event.key === 'Escape') closeDetail()
}
function metric(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : null }
function shortTime(value: string) { return timeFormatter.format(new Date(value)) }
async function select(resource: Reference) {
  detailController?.abort(); detailController = new AbortController()
  const token = ++detailRevision, signal = detailController.signal
  selected.value = resource; detail.value = null; detailError.value = ''; detailBusy.value = true; detailTab.value = 'overview'
  try { const value = await api.detail(resource, signal); if (token === detailRevision) detail.value = value }
  catch (cause) { if (token === detailRevision && !signal.aborted) detailError.value = errorMessage(cause) }
  finally { if (token === detailRevision) detailBusy.value = false }
}
async function browse(resource: Resource) {
  const child = childType(resource.type)
  if (!child) return
  parent.value = resource; query.value = ''; type.value = child
  await Promise.all([applyFilters(), select(resource)])
}
async function nextPage() {
  const removed = await list.next()
  if (removed) { await nextTick(); virtual.value?.discardRows(removed) }
}
async function loadSummary() {
  summaryController?.abort(); summaryController = new AbortController()
  const signal = summaryController.signal; summaryError.value = ''
  try { const value = await api.summary(signal); if (!signal.aborted) summary.value = value }
  catch (cause) { if (!signal.aborted) summaryError.value = errorMessage(cause) }
}
async function refreshDetail() {
  if (!selected.value) return
  const target = selected.value, token = ++detailRevision
  detailController?.abort(); detailController = new AbortController()
  const signal = detailController.signal; detailBusy.value = true; detailError.value = ''
  try { const value = await api.detail(target, signal); if (token === detailRevision) detail.value = value }
  catch (cause) { if (token === detailRevision && !signal.aborted) detailError.value = errorMessage(cause) }
  finally { if (token === detailRevision) detailBusy.value = false }
}
async function refreshCurrent(automatic = false): Promise<boolean> {
  if (refreshing.value) return false
  clearTimeout(timer); timer = undefined; refreshing.value = true
  const sameScope = appliedScope === JSON.stringify(scope())
  try {
    await Promise.all([sameScope ? list.refresh() : applyFilters(), automatic ? undefined : loadSummary(), tree.value?.refresh(), refreshDetail()])
    refreshedAt.value = new Date().toLocaleTimeString('ko-KR')
    return !list.error.value && !detailError.value && !tree.value?.hasError() && (automatic || !summaryError.value)
  } finally { refreshing.value = false }
}
function cancelPollingRequests() {
  list.stop(); tree.value?.stop(); detailController?.abort(); detailRevision++; detailBusy.value = false
}
const polling = usePolling(() => refreshCurrent(true),
  () => !refreshing.value && !list.busy.value && !detailBusy.value && !tree.value?.isBusy()
    && timer === undefined && appliedScope === JSON.stringify(scope()), cancelPollingRequests)
async function reveal() {
  if (!detail.value) return
  hierarchyVisible.value = true; await nextTick()
  await tree.value?.reveal(detail.value.path)
}
function globalPods() { closeDetail(); parent.value = null; query.value = ''; type.value = 'POD'; status.value = ''; void applyFilters() }
function date(value: string) { return dateFormatter.format(new Date(value)) }
function attributeName(key: string) { return ({ cpuUsage: 'CPU 사용률 (%)', memoryUsage: '메모리 사용률 (%)', restartCount: '재시작 횟수' } as Record<string, string>)[key] || key }
const statusLabel = (value: ResourceStatus) => ({ NORMAL: '정상', WARNING: '주의', ERROR: '오류' })[value]
onMounted(() => { void loadSummary(); void applyFilters(); polling.start(); window.addEventListener('keydown', shortcut) })
onBeforeUnmount(() => { polling.dispose(); clearTimeout(timer); list.stop(); detailController?.abort(); summaryController?.abort(); detailRevision++; window.removeEventListener('keydown', shortcut) })
</script>
<template>
  <main class="app-shell">
    <header class="topbar">
      <div class="brand"><span class="brand-mark"><UiIcon name="layers" /></span><span>Infra Monitor<small>Infrastructure console</small></span></div>
      <div class="topbar-context"><span>리소스 탐색</span><UiIcon name="chevron" /><strong>{{ type ? typeLabels[type] : '모든 유형' }}</strong><span class="environment-label">MOCK DATA</span></div>
      <div class="refresh-controls">
        <label class="polling-toggle"><input type="checkbox" :checked="polling.enabled.value" @change="polling.setEnabled(($event.target as HTMLInputElement).checked)" />자동 갱신</label>
        <span class="polling-state" :class="{ paused: !polling.enabled.value || polling.paused.value, failed: polling.failures.value > 0 }" role="status">{{ !polling.enabled.value ? '수동 갱신 모드' : polling.paused.value ? '백그라운드·오프라인 대기' : polling.failures.value ? `${polling.delay.value / 1000}초 뒤 재시도` : '자동 갱신 활성' }}</span>
        <button class="refresh-button" :disabled="refreshing" @click="refreshCurrent(false)"><UiIcon name="refresh" :class="{ spinning: refreshing }" />{{ refreshing ? '갱신 중…' : '새로고침' }}</button>
      </div>
    </header>
    <div class="console-body">
      <aside class="explorer" :class="{ 'hierarchy-open': hierarchyVisible }">
        <div class="explorer-title"><UiIcon name="layout" /><span>탐색기</span><small>READ ONLY</small><button class="hierarchy-toggle" :aria-expanded="hierarchyVisible" @click="hierarchyVisible = !hierarchyVisible">{{ hierarchyVisible ? '계층 접기' : '계층 펼치기' }}</button></div>
        <nav class="resource-navigation" aria-label="리소스 유형 바로가기">
          <p class="navigation-caption">리소스</p>
          <button v-for="resourceType in types" :key="resourceType" :class="{ active: type === resourceType && !parent && !query.trim() }" :aria-pressed="type === resourceType && !parent && !query.trim()" @click="navigateType(resourceType)">
            <UiIcon :name="resourceType" /><span>{{ typeLabels[resourceType] }}</span><small>{{ summary ? summary[summaryKeys[resourceType]].toLocaleString() : '—' }}</small>
          </button>
        </nav>
        <ResourceTree ref="tree" :selected="selectedKey" @select="select" @browse="browse" />
        <p class="explorer-note">계층 항목의 <strong>→</strong> 버튼으로<br />해당 부모의 하위 목록을 확인합니다.</p>
      </aside>
      <div class="content-area">
        <div class="page-header"><div><p class="eyebrow">RESOURCE EXPLORER</p><h1>인프라 리소스 모니터링</h1><p class="muted">리소스의 상태와 위치를 탐색하고 상세 정보를 확인합니다.</p></div><span class="scope-badge"><UiIcon name="layers" />{{ currentScopeLabel }}</span></div>
        <p v-if="summaryError" class="error" role="alert">건수 조회: {{ summaryError }} <button @click="loadSummary">다시 조회</button></p>
        <dl class="cards" aria-label="저장된 리소스 수">
          <div v-for="resourceType in types" :key="resourceType" :class="{ active: type === resourceType }"><dt>{{ typeDescriptions[resourceType] }}</dt><dd><button :aria-label="`${typeLabels[resourceType]} 전체 목록 보기`" @click="navigateType(resourceType)"><span>{{ summary ? summary[summaryKeys[resourceType]].toLocaleString() : '—' }}</span><small>{{ typeLabels[resourceType] }}</small><UiIcon :name="resourceType" /></button></dd></div>
        </dl>
        <div class="resources-layout" :class="{ 'with-detail': selected }">
          <section class="panel list-panel" aria-labelledby="list-heading">
            <div class="panel-title"><div class="list-title"><UiIcon :name="type || 'layers'" /><h2 id="list-heading">{{ query.trim() ? '검색 결과' : parent ? `${parent.name} 하위 목록` : '전체 리소스 목록' }}</h2><span class="count-tag">{{ list.rows.value.length }}</span></div><button class="text-button" @click="globalPods">전체 Pod</button></div>
            <form class="filters" @submit.prevent="applyFilters">
              <label class="search-label"><span>이름 검색</span><div class="search-input"><UiIcon name="search" /><input ref="searchField" v-model="query" maxlength="160" placeholder="리소스 이름으로 검색" @input="searchInput" /><kbd>/</kbd></div></label>
              <label>리소스 유형<select v-model="type" @change="changeType"><option v-if="query.trim()" value="">모든 유형</option><option v-for="value in types" :key="value" :value="value">{{ value }}</option></select></label>
              <label>상태<select v-model="status" @change="changeStatus"><option value="">모든 상태</option><option value="NORMAL">정상</option><option value="WARNING">주의</option><option value="ERROR">오류</option></select></label>
              <button class="primary" type="submit">조회</button>
            </form>
            <div class="scope-row"><p class="scope-note">{{ query.trim() ? '이름 검색은 선택한 유형 전체에서 수행합니다.' : parent ? '선택한 부모의 직속 하위 항목을 조회합니다.' : '서버에서 ID 순서대로 조회합니다.' }}</p><button v-if="query.trim() || status || parent" class="text-button" @click="clearFilters">조건 초기화</button></div>
            <div class="window-statuses" aria-label="현재 조회창 상태 집계"><span class="window-caption">현재 조회창</span><span class="status-count normal-text"><i />정상 {{ windowStatuses.NORMAL }}</span><span class="status-count warning-text"><i />주의 {{ windowStatuses.WARNING }}</span><span class="status-count error-text"><i />오류 {{ windowStatuses.ERROR }}</span><small>전체 DB 집계 아님</small></div>
            <div class="list-heading row-columns"><span>이름 / 경로</span><span>유형</span><span>상태</span><span>부모</span><span class="updated-column">갱신 시각</span><span /></div>
            <div class="list-body" :aria-busy="list.busy.value">
              <VirtualList ref="virtual" :items="list.rows.value" :keys="keys" label="리소스 조회 결과" :row-height="60" @end="nextPage">
                <template #default="{ item }">
                  <button class="resource-row row-columns" :class="{ selected: selectedKey === keyOf(item.resource) }" :aria-pressed="selectedKey === keyOf(item.resource)" @click="select(item.resource)">
                    <span class="resource-label"><strong>{{ item.resource.name }}</strong><small :title="item.path.map((reference: Reference) => reference.name).join(' / ')">{{ item.path.length ? item.path.map((reference: Reference) => reference.name).join(' / ') : `ID ${item.resource.id}` }}</small></span>
                    <span class="type-cell">{{ typeLabels[item.resource.type] }}</span>
                    <span class="badge" :class="item.resource.status.toLowerCase()">{{ statusLabel(item.resource.status) }}</span>
                    <span class="parent-cell" :title="item.resource.parent?.name">{{ item.resource.parent?.name || '—' }}</span>
                    <time class="updated-column" :datetime="item.resource.updatedAt" :title="date(item.resource.updatedAt)">{{ shortTime(item.resource.updatedAt) }}</time>
                    <UiIcon class="row-chevron" name="chevron" />
                  </button>
                </template>
              </VirtualList>
              <p v-if="list.busy.value" role="status" class="loading-indicator">조회 중…</p>
              <div v-if="!list.busy.value && !list.error.value && !list.rows.value.length" class="empty-list"><UiIcon name="search" /><p>조건에 맞는 리소스가 없습니다.</p><small>이름이나 상태 조건을 바꿔 다시 조회해 주세요.</small></div>
            </div>
            <p v-if="list.error.value" role="alert" class="error">{{ list.error.value }} <button @click="list.refresh">다시 조회</button></p>
            <div class="list-footer"><small>{{ list.offset.value + (list.rows.value.length ? 1 : 0) }}–{{ list.offset.value + list.rows.value.length }}번째 · 보관 {{ list.rows.value.length }}개</small><div><button v-if="list.offset.value" @click="applyFilters">처음부터</button><button v-if="list.hasMore.value" :disabled="list.busy.value || Boolean(list.error.value)" @click="nextPage">다음 100개<UiIcon name="arrow" /></button></div></div>
          </section>
          <aside v-if="selected" class="panel detail-panel" aria-labelledby="detail-heading">
            <div class="panel-title"><h2 id="detail-heading">리소스 상세</h2><span v-if="detailBusy" role="status">조회 중</span><button class="icon-button" aria-label="상세 닫기" @click="closeDetail"><UiIcon name="close" /></button></div>
            <p v-if="detailError" role="alert" class="error">{{ detailError }} <button @click="select(selected)">다시 조회</button></p>
            <template v-if="detail">
              <div class="detail-kind"><UiIcon :name="detail.resource.type" /><span>{{ typeLabels[detail.resource.type] }}</span></div><h3>{{ detail.resource.name }}</h3>
              <span class="badge" :class="detail.resource.status.toLowerCase()">{{ statusLabel(detail.resource.status) }}</span>
              <div class="detail-tabs" role="tablist" aria-label="상세 정보 탭"><button ref="overviewTab" id="overview-tab" role="tab" :tabindex="detailTab === 'overview' ? 0 : -1" :aria-selected="detailTab === 'overview'" aria-controls="overview-panel" @click="switchDetailTab('overview')" @keydown.right.prevent="switchDetailTab('path', true)" @keydown.left.prevent="switchDetailTab('path', true)">개요</button><button ref="pathTab" id="path-tab" role="tab" :tabindex="detailTab === 'path' ? 0 : -1" :aria-selected="detailTab === 'path'" aria-controls="path-panel" @click="switchDetailTab('path')" @keydown.right.prevent="switchDetailTab('overview', true)" @keydown.left.prevent="switchDetailTab('overview', true)">관계·경로</button></div>
              <div id="overview-panel" role="tabpanel" aria-labelledby="overview-tab" v-show="detailTab === 'overview'">
                <div v-if="detail.resource.type === 'NODE'" class="metric-bars"><div v-for="metricKey in ['cpuUsage', 'memoryUsage']" :key="metricKey"><div><span>{{ attributeName(metricKey) }}</span><strong>{{ metric(detail.attributes[metricKey]) ?? '—' }}%</strong></div><meter v-if="metric(detail.attributes[metricKey]) !== null" :value="metric(detail.attributes[metricKey])!" min="0" max="100" :aria-label="attributeName(metricKey)" /></div></div>
                <dl class="attributes"><div><dt>리소스 ID</dt><dd>{{ detail.resource.id }}</dd></div><div><dt>상태 갱신 시각</dt><dd>{{ date(detail.resource.updatedAt) }}</dd></div><div v-for="(value, key) in detail.attributes" :key="key"><dt>{{ attributeName(key) }}</dt><dd>{{ value }}</dd></div></dl>
              </div>
              <div id="path-panel" role="tabpanel" aria-labelledby="path-tab" v-show="detailTab === 'path'">
                <p class="muted">{{ detail.path.length }}단계 경로 · 항목을 선택해 상세로 이동합니다.</p>
                <div class="relationship-cards"><button v-for="reference in detail.path" :key="keyOf(reference)" @click="select(reference)"><UiIcon :name="reference.type" /><span><small>{{ typeLabels[reference.type] }} · ID {{ reference.id }}</small><strong>{{ reference.name }}</strong></span><UiIcon name="chevron" /></button></div>
              </div>
              <div class="detail-path"><p class="section-caption">리소스 경로</p><nav aria-label="상위 리소스 경로" class="breadcrumbs"><button v-for="reference in detail.path" :key="keyOf(reference)" @click="select(reference)"><UiIcon :name="reference.type" /><span>{{ reference.name }}</span></button></nav></div>
              <button class="reveal-button" @click="reveal"><UiIcon name="layout" />계층에서 위치 보기</button>
              <button v-if="detail.resource.hasChildren" class="primary child-button" @click="browse(detail.resource)">하위 {{ childType(detail.resource.type) }} 목록 보기<UiIcon name="arrow" /></button>
            </template>
          </aside>
        </div>
        <footer class="console-footer"><span>Mock 인프라 데이터 · 읽기 전용 탐색</span><span v-if="refreshedAt">마지막 조회 시도 {{ refreshedAt }}</span><span v-else>행을 선택하면 상세 정보를 확인할 수 있습니다.</span></footer>
      </div>
    </div>
  </main>
</template>
