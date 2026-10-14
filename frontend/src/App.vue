<script setup lang="ts">
import { onMounted, ref } from 'vue'

type Summary = { clusters: number; nodes: number; namespaces: number; pods: number }
const summary = ref<Summary | null>(null)
const error = ref('')
const loading = ref(false)

async function refresh() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await fetch('/api/resources/summary')
    if (!response.ok) throw new Error('저장된 데이터 조회에 실패했습니다.')
    summary.value = await response.json() as Summary
  } catch {
    error.value = '백엔드와 PostgreSQL의 실행 상태를 확인해 주세요.'
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <main>
    <header>
      <p class="eyebrow">INFRASTRUCTURE MONITORING</p>
      <h1>인프라 리소스 모니터링</h1>
      <p>실제 PostgreSQL에 저장된 리소스 규모를 확인합니다.</p>
    </header>
    <section aria-labelledby="summary-heading">
      <div class="section-heading">
        <h2 id="summary-heading">저장된 리소스</h2>
        <button :disabled="loading" @click="refresh">{{ loading ? '조회 중…' : '새로고침' }}</button>
      </div>
      <p v-if="error" role="alert">{{ error }}</p>
      <dl v-if="summary" class="cards">
        <div v-for="(count, resource) in summary" :key="resource">
          <dt>{{ resource }}</dt><dd>{{ count.toLocaleString() }}</dd>
        </div>
      </dl>
      <p v-else-if="loading" role="status">저장된 리소스 수를 조회하고 있습니다.</p>
    </section>
    <section>
      <h2>다음 구현 단계</h2>
      <p>Cluster → Node → Namespace → Pod 탐색, 검색·필터, 가상 스크롤 및 부분 갱신</p>
      <p>현재 화면은 초기 구성 확인용입니다. 표시 수치는 API 응답이며 임의의 샘플 숫자를 넣지 않습니다.</p>
    </section>
  </main>
</template>
