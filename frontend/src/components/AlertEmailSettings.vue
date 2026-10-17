<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'

const emit = defineEmits<{ close: [] }>()
const recipient = ref(''), enabled = ref(false), smtpConfigured = ref(false)
const ready = ref(false), busy = ref(false), error = ref(''), saved = ref(false)
const dialog = ref<HTMLElement | null>(null)
const controller = new AbortController()
function close() { emit('close') }
function onKeydown(event: KeyboardEvent) { if (event.key === 'Escape') close() }
async function load() {
  busy.value = true
  error.value = ''
  try {
    const result = await api.alertEmail(controller.signal)
    if (controller.signal.aborted) return
    recipient.value = result.recipient
    enabled.value = result.enabled
    smtpConfigured.value = result.smtpConfigured
    ready.value = result.available
  } catch {
    if (!controller.signal.aborted) error.value = '메일 설정을 확인할 수 없습니다. 인증 환경과 알림 수신기를 확인해 주세요.'
  } finally { if (!controller.signal.aborted) busy.value = false }
}
async function save() {
  busy.value = true
  saved.value = false
  error.value = ''
  try {
    const result = await api.saveAlertEmail(recipient.value.trim(), enabled.value, controller.signal)
    if (controller.signal.aborted) return
    recipient.value = result.recipient
    enabled.value = result.enabled
    smtpConfigured.value = result.smtpConfigured
    saved.value = true
  } catch {
    if (!controller.signal.aborted) error.value = '저장하지 못했습니다. 메일 주소·SMTP 설정·Alertmanager 연결을 확인해 주세요.'
  } finally { if (!controller.signal.aborted) busy.value = false }
}
onMounted(() => { void load(); window.addEventListener('keydown', onKeydown); dialog.value?.focus() })
onBeforeUnmount(() => { controller.abort(); window.removeEventListener('keydown', onKeydown) })
</script>
<template>
  <div class="alert-email-backdrop" @click.self="close">
  <section id="alert-email-dialog" ref="dialog" class="panel alert-email-settings" role="dialog" aria-modal="true" aria-labelledby="alert-email-heading" tabindex="-1">
    <div class="alert-email-heading"><h2 id="alert-email-heading">관리자 경보 메일</h2><button type="button" aria-label="메일 설정 닫기" @click="close">닫기</button></div>
    <p v-if="error" role="alert" class="error">{{ error }} <button v-if="!ready" :disabled="busy" @click="load">다시 확인</button></p>
    <form v-if="ready" @submit.prevent="save">
      <label>수신 메일 주소<input v-model="recipient" type="email" maxlength="254" autocomplete="off" placeholder="메일 주소 1개" :disabled="busy" @input="saved = false" /></label>
      <label class="email-toggle"><input v-model="enabled" type="checkbox" :disabled="busy || !smtpConfigured" @change="saved = false" />발생·해소 알림 메일 받기</label>
      <p v-if="!smtpConfigured" class="scope-note">SMTP 미설정: 수신 주소만 저장할 수 있습니다. 서버의 비공개 SMTP 설정 후 메일 수신을 활성화해 주세요.</p>
      <p v-else class="scope-note">{{ enabled ? 'Alertmanager가 등록 주소로 발생·해소 알림을 전송합니다.' : '메일 발송 중지 상태입니다. 내부 알림 수신은 유지됩니다.' }}</p>
      <button type="submit" :disabled="busy">{{ busy ? '처리 중…' : '메일 설정 저장' }}</button>
      <p v-if="saved" role="status">설정이 저장되고 Alertmanager에 적용됐습니다.</p>
    </form>
    <p v-else-if="busy" role="status">메일 설정 확인 중…</p>
  </section>
  </div>
</template>
