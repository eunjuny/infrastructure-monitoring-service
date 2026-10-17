<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { api, errorMessage, type AlertSnapshot } from '../api'
import { fullTimestamp } from '../timestamp'
import { usePolling } from '../usePolling'
import { session } from '../session'
import AlertEmailSettings from './AlertEmailSettings.vue'

const data = shallowRef<AlertSnapshot | null>(null)
const busy = ref(false)
const error = ref('')
const emailSettingsOpen = ref(false)
const states = { inactive: '조건 미충족', pending: '지속 여부 확인 중', firing: '경보 발생' }
let controller: AbortController | undefined

async function load() {
  controller?.abort()
  controller = new AbortController()
  const signal = controller.signal
  busy.value = true
  error.value = ''
  try {
    const result = await api.alerts(signal)
    if (!signal.aborted) data.value = result
    return result.available
  } catch (cause) {
    if (!signal.aborted) {
      data.value = null
      error.value = `경보 상태 확인 불가: ${errorMessage(cause)}`
    }
    return false
  } finally {
    if (!signal.aborted) busy.value = false
  }
}
const polling = usePolling(load, () => !busy.value, () => { controller?.abort(); busy.value = false }, 15000)
onMounted(() => { void load(); polling.start() })
onBeforeUnmount(() => { controller?.abort(); polling.dispose() })
</script>
<template>
  <section class="alert-panel panel" aria-labelledby="operations-alerts-title">
    <div class="alert-heading"><h2 id="operations-alerts-title">운영 경보</h2><div class="alert-actions"><button v-if="session.enabled && session.administrator" type="button" aria-haspopup="dialog" aria-controls="alert-email-dialog" @click="emailSettingsOpen = true">관리자 메일</button><button :disabled="busy" @click="load">경보 새로고침</button></div></div>
    <p v-if="busy" role="status">경보 상태 조회 중…</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="data && !data.available" role="alert" class="error">{{ data.message }}</p>
    <template v-if="data?.available">
      <p class="scope-note">최근 확인 {{ fullTimestamp(data.fetchedAt) }}</p>
      <ul class="alert-rules">
        <li v-for="rule in data.rules" :key="rule.name" :class="`alert-${rule.state}`">
          <div class="alert-heading"><strong>{{ rule.title }}</strong><span>{{ states[rule.state] }}{{ rule.state === 'firing' ? ` · ${rule.severity === 'critical' ? '심각' : '주의'}` : '' }}</span></div>
          <p>{{ rule.condition }}</p>
          <p v-if="rule.activeAt">조건 시작 {{ fullTimestamp(rule.activeAt) }} · 관측값 {{ rule.value ?? '확인 불가' }}</p>
          <details><summary>확인·대응 방법</summary><p>{{ rule.response }}</p></details>
        </li>
      </ul>
    </template>
    <details class="operations-guidance"><summary>알림 전달 안내</summary><p class="scope-note">이 화면은 Prometheus의 경보 조건을 표시합니다. 장애·해소 알림은 Alertmanager의 독립 수신 경로로 전달됩니다. 백엔드 중단 시 이 화면은 조회할 수 없으며, 조건 미충족이 서비스 전체의 정상을 보장하지는 않습니다.</p></details>
    <AlertEmailSettings v-if="emailSettingsOpen && session.enabled && session.administrator" @close="emailSettingsOpen = false" />
  </section>
</template>
